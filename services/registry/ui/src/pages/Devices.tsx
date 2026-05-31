import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Device, DeviceProfile, DeviceModeConfig, User } from '../types'

type DeviceForm = {
  device_id: string; display_name: string
  hardware_spec: string; notes: string; user_id: string
}
const emptyDevice: DeviceForm = { device_id: '', display_name: '', hardware_spec: '', notes: '', user_id: '' }

export default function DevicesPage() {
  const [devices, setDevices] = useState<Device[]>([])
  const [users, setUsers] = useState<User[]>([])
  const [error, setError] = useState('')
  const [modal, setModal] = useState<'create' | 'edit' | 'profiles' | 'pipelines' | 'claim' | null>(null)
  const [editing, setEditing] = useState<Device | null>(null)
  const [form, setForm] = useState<DeviceForm>(emptyDevice)
  const [profiles, setProfiles] = useState<DeviceProfile[]>([])
  const [profileForm, setProfileForm] = useState({ json: '', notes: '' })
  const [profileError, setProfileError] = useState('')
  const [pipelineTab, setPipelineTab] = useState<'race' | 'trip'>('race')
  const [modeConfigs, setModeConfigs] = useState<{ race: DeviceModeConfig | null; trip: DeviceModeConfig | null }>({ race: null, trip: null })
  const [pipelineError, setPipelineError] = useState('')
  const [claimForm, setClaimForm] = useState({ user_id: '', display_name: '' })
  const [claimError, setClaimError] = useState('')

  const load = () => {
    api.getDevices().then(setDevices).catch(e => setError(String(e)))
    api.getUsers().then(setUsers).catch(() => {})
  }

  useEffect(() => { load() }, [])

  const openCreate = () => { setForm(emptyDevice); setEditing(null); setModal('create') }
  const openEdit = (d: Device) => {
    setForm({
      device_id: d.device_id, display_name: d.display_name,
      hardware_spec: d.hardware_spec ?? '', notes: d.notes ?? '',
      user_id: d.user_id?.toString() ?? '',
    })
    setEditing(d); setModal('edit')
  }
  const openProfiles = async (d: Device) => {
    setEditing(d)
    setProfileForm({ json: '', notes: '' })
    setProfileError('')
    const p = await api.getProfiles(d.device_id).catch(() => [])
    setProfiles(p)
    setModal('profiles')
  }
  const openPipelines = async (d: Device) => {
    setEditing(d)
    setPipelineTab('race')
    setPipelineError('')
    const [race, trip] = await Promise.all([
      api.getModeConfig(d.device_id, 'race').catch(() => null),
      api.getModeConfig(d.device_id, 'trip').catch(() => null),
    ])
    setModeConfigs({ race, trip })
    setModal('pipelines')
  }
  const openClaim = (d: Device) => {
    setEditing(d)
    setClaimForm({ user_id: '', display_name: d.device_id })
    setClaimError('')
    setModal('claim')
  }
  const close = () => setModal(null)

  const saveDevice = async () => {
    try {
      const body = {
        device_id: form.device_id, display_name: form.display_name,
        hardware_spec: form.hardware_spec || null, notes: form.notes || null,
        user_id: form.user_id ? parseInt(form.user_id) : null,
      }
      if (modal === 'create') await api.createDevice(body)
      else if (editing) await api.updateDevice(editing.device_id, body)
      close(); load()
    } catch (e) { setError(String(e)) }
  }

  const del = async (d: Device) => {
    if (!confirm(`Delete device "${d.device_id}"?`)) return
    try { await api.deleteDevice(d.device_id); load() } catch (e) { setError(String(e)) }
  }

  const saveProfile = async () => {
    setProfileError('')
    try {
      const parsed = JSON.parse(profileForm.json)
      await api.createProfile(editing!.device_id, {
        profile_json: parsed, notes: profileForm.notes || undefined,
      })
      const p = await api.getProfiles(editing!.device_id)
      setProfiles(p)
      setProfileForm({ json: '', notes: '' })
    } catch (e) { setProfileError(String(e)) }
  }

  const activate = async (profileId: number) => {
    try {
      await api.activateProfile(editing!.device_id, profileId)
      const p = await api.getProfiles(editing!.device_id)
      setProfiles(p)
    } catch (e) { setProfileError(String(e)) }
  }

  const togglePipeline = async (pipelineName: string, currentEnabled: boolean) => {
    if (!editing) return
    setPipelineError('')
    try {
      const updated = await api.setPipelineAssignment(editing.device_id, pipelineTab, pipelineName, {
        enabled: !currentEnabled,
      })
      setModeConfigs(mc => ({ ...mc, [pipelineTab]: updated }))
    } catch (e) { setPipelineError(String(e)) }
  }

  const saveClaim = async () => {
    setClaimError('')
    if (!claimForm.user_id) { setClaimError('Please select an owner'); return }
    try {
      await api.claimDevice(editing!.device_id, {
        user_id: parseInt(claimForm.user_id),
        display_name: claimForm.display_name || undefined,
      })
      close(); load()
    } catch (e) { setClaimError(String(e)) }
  }

  const userMap = Object.fromEntries(users.map(u => [u.id, u.name]))
  const unclaimed = devices.filter(d => d.source === 'auto_detected' && !d.user_id)
  const claimed = devices.filter(d => d.source !== 'auto_detected' || d.user_id)

  const currentModeConfig = modeConfigs[pipelineTab]

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">Devices</h1>
        <button className="btn btn-primary" onClick={openCreate}>+ Add Device</button>
      </div>
      {error && <div className="error-msg">{error}</div>}

      {/* Unclaimed auto-detected devices */}
      {unclaimed.length > 0 && (
        <div style={{ background: '#2d3748', border: '1px solid #4a9eff', borderRadius: 6, padding: '12px 16px', marginBottom: 20 }}>
          <div style={{ color: '#4a9eff', fontWeight: 600, marginBottom: 8 }}>
            📡 {unclaimed.length} unclaimed device{unclaimed.length > 1 ? 's' : ''} detected
          </div>
          <table>
            <thead>
              <tr><th>Device ID</th><th>Last Seen</th><th>Last Mode</th><th></th></tr>
            </thead>
            <tbody>
              {unclaimed.map(d => (
                <tr key={d.id}>
                  <td><code>{d.device_id}</code></td>
                  <td>{d.last_seen_at ? new Date(d.last_seen_at).toLocaleString() : '—'}</td>
                  <td>{d.last_mode ? <span className="badge badge-green">{d.last_mode}</span> : '—'}</td>
                  <td>
                    <div className="row-actions">
                      <button className="btn btn-primary btn-sm" onClick={() => openClaim(d)}>Claim</button>
                      <button className="btn btn-danger btn-sm" onClick={() => del(d)}>Dismiss</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Registered devices */}
      {claimed.length === 0 ? (
        <div className="empty-msg">No devices yet.</div>
      ) : (
        <table>
          <thead>
            <tr><th>Device ID</th><th>Display Name</th><th>Hardware</th><th>Owner</th><th>Last Seen</th><th></th></tr>
          </thead>
          <tbody>
            {claimed.map(d => (
              <tr key={d.id}>
                <td><code>{d.device_id}</code></td>
                <td>{d.display_name}</td>
                <td>{d.hardware_spec ?? <span style={{ color: '#4a5568' }}>—</span>}</td>
                <td>{d.user_id ? userMap[d.user_id] ?? d.user_id : <span style={{ color: '#4a5568' }}>—</span>}</td>
                <td style={{ fontSize: '0.8rem', color: '#718096' }}>
                  {d.last_seen_at ? new Date(d.last_seen_at).toLocaleString() : '—'}
                </td>
                <td>
                  <div className="row-actions">
                    <button className="btn btn-secondary btn-sm" onClick={() => openPipelines(d)}>Pipelines</button>
                    <button className="btn btn-secondary btn-sm" onClick={() => openProfiles(d)}>Profiles</button>
                    <button className="btn btn-secondary btn-sm" onClick={() => openEdit(d)}>Edit</button>
                    <button className="btn btn-danger btn-sm" onClick={() => del(d)}>Delete</button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* Create / Edit Device modal */}
      {(modal === 'create' || modal === 'edit') && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h2>{modal === 'create' ? 'Add Device' : 'Edit Device'}</h2>
            {modal === 'create' && (
              <div className="form-group">
                <label>Device ID</label>
                <input type="text" placeholder="SCRAPS-001"
                  value={form.device_id} onChange={e => setForm(f => ({ ...f, device_id: e.target.value }))} />
              </div>
            )}
            <div className="form-group">
              <label>Display Name</label>
              <input type="text" value={form.display_name}
                onChange={e => setForm(f => ({ ...f, display_name: e.target.value }))} />
            </div>
            <div className="form-group">
              <label>Hardware Spec</label>
              <input type="text" placeholder="GT-U7 + MPU-6050"
                value={form.hardware_spec} onChange={e => setForm(f => ({ ...f, hardware_spec: e.target.value }))} />
            </div>
            <div className="form-group">
              <label>Owner</label>
              <select value={form.user_id} onChange={e => setForm(f => ({ ...f, user_id: e.target.value }))}>
                <option value="">— unassigned —</option>
                {users.map(u => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
            </div>
            <div className="form-group">
              <label>Notes</label>
              <textarea value={form.notes} onChange={e => setForm(f => ({ ...f, notes: e.target.value }))} />
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={close}>Cancel</button>
              <button className="btn btn-primary" onClick={saveDevice}>Save</button>
            </div>
          </div>
        </div>
      )}

      {/* Claim modal */}
      {modal === 'claim' && editing && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h2>Claim Device — <code>{editing.device_id}</code></h2>
            <div className="form-group">
              <label>Owner</label>
              <select value={claimForm.user_id} onChange={e => setClaimForm(f => ({ ...f, user_id: e.target.value }))}>
                <option value="">— select owner —</option>
                {users.map(u => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
            </div>
            <div className="form-group">
              <label>Display Name</label>
              <input type="text" value={claimForm.display_name}
                onChange={e => setClaimForm(f => ({ ...f, display_name: e.target.value }))} />
            </div>
            {claimError && <div className="error-msg">{claimError}</div>}
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={close}>Cancel</button>
              <button className="btn btn-primary" onClick={saveClaim}>Claim</button>
            </div>
          </div>
        </div>
      )}

      {/* Profiles modal */}
      {modal === 'profiles' && editing && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal modal-wide" onClick={e => e.stopPropagation()}>
            <h2>Profiles — {editing.device_id}</h2>

            {profiles.length > 0 && (
              <table style={{ marginBottom: '1.5rem' }}>
                <thead><tr><th>Version</th><th>Status</th><th>Notes</th><th>Created</th><th></th></tr></thead>
                <tbody>
                  {profiles.map(p => (
                    <tr key={p.id}>
                      <td>v{p.version}</td>
                      <td>
                        {p.active
                          ? <span className="badge badge-green">active</span>
                          : <span className="badge badge-gray">inactive</span>}
                      </td>
                      <td>{p.notes ?? '—'}</td>
                      <td>{new Date(p.created_at).toLocaleDateString()}</td>
                      <td>
                        {!p.active && (
                          <button className="btn btn-success btn-sm" onClick={() => activate(p.id)}>
                            Set Active
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <h2 style={{ fontSize: '0.95rem', marginBottom: '0.75rem' }}>Add New Profile Version</h2>
            {profileError && <div className="error-msg">{profileError}</div>}
            <div className="form-group">
              <label>Profile JSON</label>
              <textarea className="code" placeholder='{"geofence_radius_m": 40, ...}'
                value={profileForm.json}
                onChange={e => setProfileForm(f => ({ ...f, json: e.target.value }))} />
            </div>
            <div className="form-group">
              <label>Notes (optional)</label>
              <input type="text" value={profileForm.notes}
                onChange={e => setProfileForm(f => ({ ...f, notes: e.target.value }))} />
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={close}>Close</button>
              <button className="btn btn-primary" onClick={saveProfile}>Save & Activate</button>
            </div>
          </div>
        </div>
      )}

      {/* Pipeline config modal */}
      {modal === 'pipelines' && editing && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal modal-wide" onClick={e => e.stopPropagation()}>
            <h2>Pipeline Config — {editing.device_id}</h2>
            <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
              {(['race', 'trip'] as const).map(m => (
                <button
                  key={m}
                  className={`btn ${pipelineTab === m ? 'btn-primary' : 'btn-secondary'}`}
                  onClick={() => setPipelineTab(m)}
                >
                  {m === 'race' ? '🏁 Race' : '🚗 Trip'}
                </button>
              ))}
            </div>
            {pipelineError && <div className="error-msg">{pipelineError}</div>}
            {currentModeConfig ? (
              <table>
                <thead>
                  <tr><th>Pipeline</th><th>Enabled</th></tr>
                </thead>
                <tbody>
                  {currentModeConfig.assignments.map(a => (
                    <tr key={a.pipeline_id}>
                      <td><code>{a.pipeline_name}</code></td>
                      <td>
                        <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
                          <input
                            type="checkbox"
                            checked={a.enabled}
                            onChange={() => togglePipeline(a.pipeline_name, a.enabled)}
                          />
                          {a.enabled
                            ? <span className="badge badge-green">enabled</span>
                            : <span className="badge badge-gray">disabled</span>}
                        </label>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <div className="empty-msg">Loading…</div>
            )}
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={close}>Close</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
