import { useEffect, useRef, useState } from 'react'
import type { OsmCandidate, Track } from '../types'
import { api } from '../api'

// Palette for rendering multiple OSM candidates on the discovery map.
const CANDIDATE_COLORS = [
  '#f6c90e', '#3fc1c9', '#fc5c65', '#45b97c', '#a55eea',
  '#fd9644', '#2bcbba', '#e84393', '#26de81', '#fd7272',
]

type TrackForm = {
  name: string; country: string; surface_type: string
  source: string; osm_relation_id: string; notes: string
  geometry_json: string; start_line_json: string
}
const emptyForm: TrackForm = {
  name: '', country: '', surface_type: 'asphalt', source: 'osm',
  osm_relation_id: '', notes: '', geometry_json: '', start_line_json: '',
}

export default function TracksPage() {
  const [tracks, setTracks] = useState<Track[]>([])
  const [error, setError] = useState('')
  const [modal, setModal] = useState<'create' | 'edit' | 'view' | 'osm' | null>(null)
  const [editing, setEditing] = useState<Track | null>(null)
  const [form, setForm] = useState<TrackForm>(emptyForm)
  const [formError, setFormError] = useState('')
  const mapRef = useRef<HTMLDivElement>(null)
  const leafletRef = useRef<import('leaflet').Map | null>(null)

  // OSM discover state
  const osmMapRef = useRef<HTMLDivElement>(null)
  const osmLeafletRef = useRef<import('leaflet').Map | null>(null)
  const osmMarkerRef = useRef<import('leaflet').Marker | null>(null)
  const osmCandidateLayers = useRef<import('leaflet').Layer[]>([])
  const [osmLat, setOsmLat] = useState('')
  const [osmLon, setOsmLon] = useState('')
  const [osmRadius, setOsmRadius] = useState('5000')
  const [osmToken, setOsmToken] = useState('')
  const [osmCandidates, setOsmCandidates] = useState<OsmCandidate[]>([])
  const [osmSelected, setOsmSelected] = useState<Set<number>>(new Set())
  const [osmDiscovering, setOsmDiscovering] = useState(false)
  const [osmIngesting, setOsmIngesting] = useState(false)
  const [osmResult, setOsmResult] = useState<{ ingested: Track[]; skipped: { name: string; reason: string }[] } | null>(null)
  const [osmError, setOsmError] = useState('')

  const load = () => api.getTracks().then(setTracks).catch(e => setError(String(e)))

  useEffect(() => { load() }, [])

  // ── Track map view ──────────────────────────────────────────────────────────
  useEffect(() => {
    if (modal !== 'view' || !mapRef.current || !editing) return

    import('leaflet').then(L => {
      if (leafletRef.current) {
        leafletRef.current.remove()
        leafletRef.current = null
      }
      const map = L.map(mapRef.current!).setView([0, 0], 2)
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© OpenStreetMap contributors',
      }).addTo(map)

      if (editing.geometry) {
        try {
          const layer = L.geoJSON(editing.geometry as unknown as Parameters<typeof L.geoJSON>[0])
          layer.addTo(map)
          const bounds = layer.getBounds()
          if (bounds.isValid()) map.fitBounds(bounds, { padding: [20, 20] })
        } catch (_) {}
      }

      if (editing.start_line) {
        try {
          const sl = editing.start_line as { coordinates?: [number, number] }
          if (sl.coordinates) {
            const [lng, lat] = sl.coordinates
            L.marker([lat, lng]).bindPopup('Start / Finish').addTo(map)
          }
        } catch (_) {}
      }

      leafletRef.current = map
    })

    return () => {
      leafletRef.current?.remove()
      leafletRef.current = null
    }
  }, [modal, editing])

  // ── OSM discovery map ───────────────────────────────────────────────────────
  useEffect(() => {
    if (modal !== 'osm' || !osmMapRef.current) return

    import('leaflet').then(L => {
      if (osmLeafletRef.current) return  // already mounted

      const map = L.map(osmMapRef.current!).setView([39.5, -98.35], 4)
      // Esri satellite tiles — same source as the draw tool
      L.tileLayer(
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        { attribution: 'Tiles © Esri', maxZoom: 19 },
      ).addTo(map)

      map.on('click', (e: import('leaflet').LeafletMouseEvent) => {
        const { lat, lng } = e.latlng
        setOsmLat(lat.toFixed(6))
        setOsmLon(lng.toFixed(6))

        if (osmMarkerRef.current) osmMarkerRef.current.remove()
        osmMarkerRef.current = L.marker([lat, lng])
          .bindPopup(`${lat.toFixed(5)}, ${lng.toFixed(5)}`)
          .addTo(map)
          .openPopup()
      })

      osmLeafletRef.current = map
    })

    return () => {
      osmLeafletRef.current?.remove()
      osmLeafletRef.current = null
      osmMarkerRef.current = null
    }
  }, [modal])

  // Render candidate geometries on the OSM map whenever candidates change.
  useEffect(() => {
    const map = osmLeafletRef.current
    if (!map) return

    import('leaflet').then(L => {
      osmCandidateLayers.current.forEach(l => map.removeLayer(l))
      osmCandidateLayers.current = []

      osmCandidates.forEach((c, i) => {
        if (!c.geometry) return
        const color = CANDIDATE_COLORS[i % CANDIDATE_COLORS.length]
        const selected = osmSelected.has(i)
        const layer = L.geoJSON(c.geometry as unknown as Parameters<typeof L.geoJSON>[0], {
          style: {
            color,
            weight: selected ? 4 : 2,
            opacity: c.already_imported ? 0.4 : 1,
            fillOpacity: 0.15,
          },
        })
          .bindTooltip(`${i + 1}. ${c.name}${c.already_imported ? ' (imported)' : ''}`)
          .addTo(map)

        osmCandidateLayers.current.push(layer)
      })

      if (osmCandidates.length > 0 && osmCandidateLayers.current.length > 0) {
        const group = L.featureGroup(osmCandidateLayers.current)
        const bounds = group.getBounds()
        if (bounds.isValid()) map.fitBounds(bounds, { padding: [30, 30] })
      }
    })
  }, [osmCandidates, osmSelected])

  // ── OSM actions ─────────────────────────────────────────────────────────────
  const runDiscover = async () => {
    if (!osmLat || !osmLon) { setOsmError('Click the map to set a location first.'); return }
    setOsmError(''); setOsmResult(null); setOsmDiscovering(true)
    try {
      const res = await api.discoverTracks({
        lat: parseFloat(osmLat),
        lon: parseFloat(osmLon),
        radius_m: parseInt(osmRadius) || 5000,
      })
      setOsmToken(res.token)
      setOsmCandidates(res.candidates)
      // Pre-select all non-imported candidates.
      setOsmSelected(new Set(res.candidates.map((_, i) => i).filter(i => !res.candidates[i].already_imported)))
    } catch (e) { setOsmError(String(e)) }
    finally { setOsmDiscovering(false) }
  }

  const runIngest = async () => {
    if (osmSelected.size === 0) { setOsmError('Select at least one track to import.'); return }
    setOsmError(''); setOsmIngesting(true)
    try {
      const result = await api.ingestTracks(osmToken, Array.from(osmSelected).sort((a, b) => a - b))
      setOsmResult(result)
      load()
    } catch (e) { setOsmError(String(e)) }
    finally { setOsmIngesting(false) }
  }

  const toggleOsmSelected = (i: number) => {
    const c = osmCandidates[i]
    if (c.already_imported) return  // cannot re-import
    setOsmSelected(prev => {
      const next = new Set(prev)
      next.has(i) ? next.delete(i) : next.add(i)
      return next
    })
  }

  const openOsm = () => {
    setOsmLat(''); setOsmLon(''); setOsmRadius('5000')
    setOsmToken(''); setOsmCandidates([]); setOsmSelected(new Set())
    setOsmResult(null); setOsmError('')
    setModal('osm')
  }

  // ── Form helpers ─────────────────────────────────────────────────────────────
  const openCreate = () => { setForm(emptyForm); setEditing(null); setFormError(''); setModal('create') }
  const openEdit = (t: Track) => {
    setForm({
      name: t.name, country: t.country ?? '', surface_type: t.surface_type ?? 'asphalt',
      source: t.source, osm_relation_id: t.osm_relation_id?.toString() ?? '',
      notes: t.notes ?? '',
      geometry_json: t.geometry ? JSON.stringify(t.geometry, null, 2) : '',
      start_line_json: t.start_line ? JSON.stringify(t.start_line, null, 2) : '',
    })
    setEditing(t); setFormError(''); setModal('edit')
  }
  const openView = (t: Track) => { setEditing(t); setModal('view') }
  const close = () => setModal(null)

  const parseOptionalJson = (s: string) => {
    if (!s.trim()) return null
    return JSON.parse(s)
  }

  const saveTrack = async () => {
    setFormError('')
    try {
      const body = {
        name: form.name, country: form.country || null,
        surface_type: form.surface_type || null, source: form.source,
        osm_relation_id: form.osm_relation_id ? parseInt(form.osm_relation_id) : null,
        notes: form.notes || null,
        geometry: parseOptionalJson(form.geometry_json),
        start_line: parseOptionalJson(form.start_line_json),
      }
      if (modal === 'create') await api.createTrack(body)
      else if (editing) await api.updateTrack(editing.id, body)
      close(); load()
    } catch (e) { setFormError(String(e)) }
  }

  const handleImportFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = ev => {
      try {
        const geojson = JSON.parse(ev.target?.result as string)
        setForm(f => ({ ...f, geometry_json: JSON.stringify(geojson, null, 2) }))
        const feat = geojson.features?.[0] ?? geojson
        const name = feat?.properties?.name ?? feat?.properties?.alt_name ?? ''
        if (name) setForm(f => ({ ...f, name }))
      } catch (_) { setFormError('Invalid GeoJSON file') }
    }
    reader.readAsText(file)
  }

  const del = async (t: Track) => {
    if (!confirm(`Delete track "${t.name}"?`)) return
    try { await api.deleteTrack(t.id); load() } catch (e) { setError(String(e)) }
  }

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">Tracks</h1>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button className="btn btn-secondary" onClick={openOsm}>Import from OSM</button>
          <button className="btn btn-primary" onClick={openCreate}>+ Add Track</button>
        </div>
      </div>
      {error && <div className="error-msg">{error}</div>}
      {tracks.length === 0 ? (
        <div className="empty-msg">No tracks yet.</div>
      ) : (
        <table>
          <thead>
            <tr><th>Name</th><th>Country</th><th>Surface</th><th>Source</th><th>Geometry</th><th></th></tr>
          </thead>
          <tbody>
            {tracks.map(t => (
              <tr key={t.id}>
                <td>{t.name}</td>
                <td>{t.country ?? '—'}</td>
                <td>{t.surface_type ?? '—'}</td>
                <td>
                  <span className={`badge ${t.source === 'osm' ? 'badge-green' : 'badge-gray'}`}>
                    {t.source}
                  </span>
                </td>
                <td>{t.geometry ? '✓' : <span style={{ color: '#4a5568' }}>—</span>}</td>
                <td>
                  <div className="row-actions">
                    {t.geometry && (
                      <button className="btn btn-secondary btn-sm" onClick={() => openView(t)}>Map</button>
                    )}
                    <button className="btn btn-secondary btn-sm" onClick={() => openEdit(t)}>Edit</button>
                    <button className="btn btn-danger btn-sm" onClick={() => del(t)}>Delete</button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* Create / Edit modal */}
      {(modal === 'create' || modal === 'edit') && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal modal-wide" onClick={e => e.stopPropagation()}>
            <h2>{modal === 'create' ? 'Add Track' : 'Edit Track'}</h2>
            {formError && <div className="error-msg">{formError}</div>}
            <div className="form-group">
              <label>Name</label>
              <input type="text" value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
            </div>
            <div style={{ display: 'flex', gap: '1rem' }}>
              <div className="form-group" style={{ flex: 1 }}>
                <label>Country</label>
                <input type="text" value={form.country}
                  onChange={e => setForm(f => ({ ...f, country: e.target.value }))} />
              </div>
              <div className="form-group" style={{ flex: 1 }}>
                <label>Surface</label>
                <select value={form.surface_type}
                  onChange={e => setForm(f => ({ ...f, surface_type: e.target.value }))}>
                  <option value="asphalt">Asphalt</option>
                  <option value="concrete">Concrete</option>
                  <option value="dirt">Dirt</option>
                  <option value="gravel">Gravel</option>
                </select>
              </div>
              <div className="form-group" style={{ flex: 1 }}>
                <label>Source</label>
                <select value={form.source}
                  onChange={e => setForm(f => ({ ...f, source: e.target.value }))}>
                  <option value="osm">OSM</option>
                  <option value="user_built">User Built</option>
                </select>
              </div>
            </div>
            <div style={{ display: 'flex', gap: '1rem' }}>
              <div className="form-group" style={{ flex: 1 }}>
                <label>OSM Relation ID</label>
                <input type="text" value={form.osm_relation_id}
                  onChange={e => setForm(f => ({ ...f, osm_relation_id: e.target.value }))} />
              </div>
            </div>
            <div className="form-group">
              <label>
                Geometry (GeoJSON)&nbsp;
                <label style={{ display: 'inline', textTransform: 'none', fontSize: '11px', color: '#718096' }}>
                  — or import file: <input type="file" accept=".geojson,.json"
                    style={{ display: 'inline', width: 'auto' }}
                    onChange={handleImportFile} />
                </label>
              </label>
              <textarea className="code" placeholder='{"type":"FeatureCollection","features":[...]}'
                value={form.geometry_json}
                onChange={e => setForm(f => ({ ...f, geometry_json: e.target.value }))} />
            </div>
            <div className="form-group">
              <label>Start / Finish Line (GeoJSON Point)</label>
              <textarea className="code" style={{ minHeight: '80px' }}
                placeholder='{"type":"Point","coordinates":[-112.0,37.0]}'
                value={form.start_line_json}
                onChange={e => setForm(f => ({ ...f, start_line_json: e.target.value }))} />
            </div>
            <div className="form-group">
              <label>Notes</label>
              <textarea value={form.notes}
                onChange={e => setForm(f => ({ ...f, notes: e.target.value }))} />
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={close}>Cancel</button>
              <button className="btn btn-primary" onClick={saveTrack}>Save</button>
            </div>
          </div>
        </div>
      )}

      {/* Map view modal */}
      {modal === 'view' && editing && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal modal-wide" onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
              <h2 style={{ margin: 0 }}>{editing.name}</h2>
              <button className="btn btn-secondary btn-sm" onClick={close}>Close</button>
            </div>
            <div className="map-container" ref={mapRef} />
          </div>
        </div>
      )}

      {/* OSM import modal */}
      {modal === 'osm' && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal modal-wide" style={{ maxWidth: '860px' }} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
              <h2 style={{ margin: 0 }}>Import from OpenStreetMap</h2>
              <button className="btn btn-secondary btn-sm" onClick={close}>Close</button>
            </div>

            <p style={{ color: '#718096', fontSize: '13px', marginTop: 0 }}>
              Click the map to set a search location, then click Discover.
            </p>

            <div className="map-container" ref={osmMapRef} style={{ height: '300px', marginBottom: '1rem' }} />

            <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'flex-end', marginBottom: '1rem' }}>
              <div className="form-group" style={{ flex: 1, margin: 0 }}>
                <label>Latitude</label>
                <input type="text" value={osmLat} readOnly placeholder="click map"
                  onChange={e => setOsmLat(e.target.value)} />
              </div>
              <div className="form-group" style={{ flex: 1, margin: 0 }}>
                <label>Longitude</label>
                <input type="text" value={osmLon} readOnly placeholder="click map"
                  onChange={e => setOsmLon(e.target.value)} />
              </div>
              <div className="form-group" style={{ flex: 1, margin: 0 }}>
                <label>Radius (m)</label>
                <input type="number" value={osmRadius} min="500" max="50000" step="500"
                  onChange={e => setOsmRadius(e.target.value)} />
              </div>
              <button className="btn btn-primary" onClick={runDiscover} disabled={osmDiscovering}>
                {osmDiscovering ? 'Searching…' : 'Discover'}
              </button>
            </div>

            {osmError && <div className="error-msg" style={{ marginBottom: '0.75rem' }}>{osmError}</div>}

            {osmCandidates.length > 0 && !osmResult && (
              <>
                <div style={{ marginBottom: '0.5rem', fontSize: '13px', color: '#a0aec0' }}>
                  {osmCandidates.length} track{osmCandidates.length !== 1 ? 's' : ''} found.
                  Select the ones to import.
                </div>
                <div style={{ border: '1px solid #2d3748', borderRadius: '6px', overflow: 'hidden', marginBottom: '1rem' }}>
                  {osmCandidates.map((c, i) => (
                    <div
                      key={i}
                      onClick={() => toggleOsmSelected(i)}
                      style={{
                        display: 'flex', alignItems: 'center', gap: '0.75rem',
                        padding: '0.6rem 0.85rem',
                        borderBottom: i < osmCandidates.length - 1 ? '1px solid #2d3748' : undefined,
                        cursor: c.already_imported ? 'default' : 'pointer',
                        opacity: c.already_imported ? 0.5 : 1,
                        background: osmSelected.has(i) ? '#1a2744' : 'transparent',
                      }}
                    >
                      <input
                        type="checkbox"
                        checked={osmSelected.has(i)}
                        disabled={c.already_imported}
                        onChange={() => toggleOsmSelected(i)}
                        onClick={e => e.stopPropagation()}
                        style={{ accentColor: CANDIDATE_COLORS[i % CANDIDATE_COLORS.length] }}
                      />
                      <span
                        style={{
                          display: 'inline-block', width: 12, height: 12, borderRadius: 2,
                          background: CANDIDATE_COLORS[i % CANDIDATE_COLORS.length], flexShrink: 0,
                        }}
                      />
                      <span style={{ flex: 1 }}>{c.name}</span>
                      <span style={{ fontSize: '11px', color: '#718096' }}>
                        {c.geometry_type}
                        {c.osm_relation_id ? ` · rel ${c.osm_relation_id}` : ''}
                        {c.osm_way_ids ? ` · ${c.osm_way_ids.length} way${c.osm_way_ids.length !== 1 ? 's' : ''}` : ''}
                      </span>
                      {c.already_imported && (
                        <span className="badge badge-gray" style={{ fontSize: '10px' }}>imported</span>
                      )}
                    </div>
                  ))}
                </div>
                <div className="modal-actions">
                  <span style={{ fontSize: '13px', color: '#718096', alignSelf: 'center' }}>
                    {osmSelected.size} selected
                  </span>
                  <button className="btn btn-primary" onClick={runIngest} disabled={osmIngesting || osmSelected.size === 0}>
                    {osmIngesting ? 'Importing…' : `Import Selected (${osmSelected.size})`}
                  </button>
                </div>
              </>
            )}

            {osmResult && (
              <div style={{ padding: '0.85rem', background: '#1a2a1a', borderRadius: '6px', border: '1px solid #276127' }}>
                <div style={{ fontWeight: 600, marginBottom: '0.4rem', color: '#68d391' }}>
                  ✓ {osmResult.ingested.length} track{osmResult.ingested.length !== 1 ? 's' : ''} imported
                </div>
                {osmResult.ingested.map(t => (
                  <div key={t.id} style={{ fontSize: '13px', color: '#a0aec0' }}>• {t.name}</div>
                ))}
                {osmResult.skipped.length > 0 && (
                  <div style={{ marginTop: '0.75rem' }}>
                    <div style={{ fontWeight: 600, marginBottom: '0.4rem', color: '#f6ad55' }}>
                      {osmResult.skipped.length} skipped
                    </div>
                    {osmResult.skipped.map((s, i) => (
                      <div key={i} style={{ fontSize: '13px', color: '#a0aec0' }}>• {s.name} — {s.reason}</div>
                    ))}
                  </div>
                )}
                <button className="btn btn-secondary btn-sm" style={{ marginTop: '0.75rem' }} onClick={close}>Done</button>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  )
}
