import { useEffect, useRef, useState } from 'react'
import type { Track } from '../types'
import { api } from '../api'

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
  const [modal, setModal] = useState<'create' | 'edit' | 'view' | null>(null)
  const [editing, setEditing] = useState<Track | null>(null)
  const [form, setForm] = useState<TrackForm>(emptyForm)
  const [formError, setFormError] = useState('')
  const mapRef = useRef<HTMLDivElement>(null)
  const leafletRef = useRef<import('leaflet').Map | null>(null)

  const load = () => api.getTracks().then(setTracks).catch(e => setError(String(e)))

  useEffect(() => { load() }, [])

  // Mount Leaflet map when view modal is open
  useEffect(() => {
    if (modal !== 'view' || !mapRef.current || !editing) return

    // Lazy-load Leaflet to avoid SSR issues
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
            L.marker([lat, lng])
              .bindPopup('Start / Finish')
              .addTo(map)
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
        // Try to prefill name from properties
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
        <button className="btn btn-primary" onClick={openCreate}>+ Add Track</button>
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
    </>
  )
}
