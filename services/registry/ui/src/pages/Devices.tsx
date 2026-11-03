import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Device, User } from '../types'

// Devices register themselves (kreceiver's heartbeat) and get their owner from the
// account they publish as; editing the owner here is a manual override.

type DeviceForm = { display_name: string; hardware_spec: string; notes: string; user_id: string }

export default function DevicesPage() {
  const [devices, setDevices] = useState<Device[]>([])
  const [users, setUsers] = useState<User[]>([])
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<Device | null>(null)
  const [form, setForm] = useState<DeviceForm>({ display_name: '', hardware_spec: '', notes: '', user_id: '' })

  const load = () => {
    api.getDevices().then(setDevices).catch(e => setError(String(e)))
    api.getUsers().then(setUsers).catch(() => {})
  }

  useEffect(() => { load() }, [])

  const openEdit = (d: Device) => {
    setForm({
      display_name: d.display_name, hardware_spec: d.hardware_spec ?? '',
      notes: d.notes ?? '', user_id: d.user_id?.toString() ?? '',
    })
    setEditing(d)
  }
  const close = () => setEditing(null)

  const save = async () => {
    if (!editing) return
    try {
      await api.updateDevice(editing.device_id, {
        display_name: form.display_name, hardware_spec: form.hardware_spec || null,
        notes: form.notes || null, user_id: form.user_id ? parseInt(form.user_id) : null,
      })
      close(); load()
    } catch (e) { setError(String(e)) }
  }

  const del = async (d: Device) => {
    if (!confirm(`Delete device "${d.device_id}"?`)) return
    try { await api.deleteDevice(d.device_id); load() } catch (e) { setError(String(e)) }
  }

  const muted = { color: '#4a5568' }

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">Devices</h1>
      </div>
      {error && <div className="error-msg">{error}</div>}
      {devices.length === 0 ? (
        <div className="empty-msg">No devices yet. A device appears here the first time it publishes.</div>
      ) : (
        <table>
          <thead>
            <tr><th>Device ID</th><th>Display Name</th><th>Hardware</th><th>Owner</th><th>Last Seen</th><th></th></tr>
          </thead>
          <tbody>
            {devices.map(d => (
              <tr key={d.id}>
                <td><code>{d.device_id}</code></td>
                <td>{d.display_name}</td>
                <td>{d.hardware_spec ?? <span style={muted}>—</span>}</td>
                <td>{d.owner_email ?? <span style={muted}>not linked yet</span>}</td>
                <td style={{ fontSize: '0.8rem', color: '#718096' }}>
                  {d.last_seen_at ? new Date(d.last_seen_at).toLocaleString() : '—'}
                </td>
                <td>
                  <div className="row-actions">
                    <button className="btn btn-secondary btn-sm" onClick={() => openEdit(d)}>Edit</button>
                    <button className="btn btn-danger btn-sm" onClick={() => del(d)}>Delete</button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {editing && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h2>Edit Device — <code>{editing.device_id}</code></h2>
            <div className="form-group">
              <label>Display Name</label>
              <input type="text" value={form.display_name}
                onChange={e => setForm(f => ({ ...f, display_name: e.target.value }))} />
            </div>
            <div className="form-group">
              <label>Hardware Spec</label>
              <input type="text" placeholder="XIAO ESP32-C6 dongle + S21 Ultra"
                value={form.hardware_spec} onChange={e => setForm(f => ({ ...f, hardware_spec: e.target.value }))} />
            </div>
            <div className="form-group">
              <label>Owner (set automatically from the account it publishes as)</label>
              <select value={form.user_id} onChange={e => setForm(f => ({ ...f, user_id: e.target.value }))}>
                <option value="">— unassigned —</option>
                {users.map(u => <option key={u.id} value={u.id}>{u.name} ({u.email})</option>)}
              </select>
            </div>
            <div className="form-group">
              <label>Notes</label>
              <textarea value={form.notes} onChange={e => setForm(f => ({ ...f, notes: e.target.value }))} />
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={close}>Cancel</button>
              <button className="btn btn-primary" onClick={save}>Save</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
