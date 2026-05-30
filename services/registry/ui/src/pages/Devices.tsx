import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Device, DeviceProfile, User } from '../types'

type DeviceForm = {
  device_id: string; display_name: string
  hardware_spec: string; notes: string; user_id: string
}
const emptyDevice: DeviceForm = { device_id: '', display_name: '', hardware_spec: '', notes: '', user_id: '' }

export default function DevicesPage() {
  const [devices, setDevices] = useState<Device[]>([])
  const [users, setUsers] = useState<User[]>([])
  const [error, setError] = useState('')
  const [modal, setModal] = useState<'create' | 'edit' | 'profiles' | null>(null)
  const [editing, setEditing] = useState<Device | null>(null)
  const [form, setForm] = useState<DeviceForm>(emptyDevice)
  const [profiles, setProfiles] = useState<DeviceProfile[]>([])
  const [profileForm, setProfileForm] = useState({ json: '', notes: '' })
  const [profileError, setProfileError] = useState('')

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

  const userMap = Object.fromEntries(users.map(u => [u.id, u.name]))

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">Devices</h1>
        <button className="btn btn-primary" onClick={openCreate}>+ Add Device</button>
      </div>
      {error && <div className="error-msg">{error}</div>}
      {devices.length === 0 ? (
        <div className="empty-msg">No devices yet.</div>
      ) : (
        <table>
          <thead>
            <tr><th>Device ID</th><th>Display Name</th><th>Hardware</th><th>Owner</th><th>Created</th><th></th></tr>
          </thead>
          <tbody>
            {devices.map(d => (
              <tr key={d.id}>
                <td><code>{d.device_id}</code></td>
                <td>{d.display_name}</td>
                <td>{d.hardware_spec ?? <span style={{ color: '#4a5568' }}>—</span>}</td>
                <td>{d.user_id ? userMap[d.user_id] ?? d.user_id : <span style={{ color: '#4a5568' }}>—</span>}</td>
                <td>{new Date(d.created_at).toLocaleDateString()}</td>
                <td>
                  <div className="row-actions">
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

      {/* Profiles modal */}
      {modal === 'profiles' && editing && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal modal-wide" onClick={e => e.stopPropagation()}>
            <h2>Profiles — {editing.device_id}</h2>

            {/* Version history */}
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

            {/* New profile form */}
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
    </>
  )
}
