import { useEffect, useState } from 'react'
import { api } from '../api'
import type { User } from '../types'

type FormState = { name: string; email: string }

// Users are created by signing in to the phone app; this page lists and edits them.

export default function UsersPage() {
  const [users, setUsers] = useState<User[]>([])
  const [error, setError] = useState('')
  const [modal, setModal] = useState<'edit' | null>(null)
  const [editing, setEditing] = useState<User | null>(null)
  const [form, setForm] = useState<FormState>({ name: '', email: '' })

  const load = () => api.getUsers().then(setUsers).catch(e => setError(String(e)))

  useEffect(() => { load() }, [])

  const openEdit = (u: User) => { setForm({ name: u.name, email: u.email }); setEditing(u); setModal('edit') }
  const close = () => setModal(null)

  const save = async () => {
    try {
      if (editing) await api.updateUser(editing.id, form)
      close(); load()
    } catch (e) { setError(String(e)) }
  }

  const del = async (u: User) => {
    if (!confirm(`Delete user "${u.name}"?`)) return
    try { await api.deleteUser(u.id); load() } catch (e) { setError(String(e)) }
  }

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">Users</h1>
      </div>
      {error && <div className="error-msg">{error}</div>}
      {users.length === 0 ? (
        <div className="empty-msg">No users yet. A user appears here the first time they sign in to the app.</div>
      ) : (
        <table>
          <thead><tr><th>Name</th><th>Email</th><th>Created</th><th></th></tr></thead>
          <tbody>
            {users.map(u => (
              <tr key={u.id}>
                <td>{u.name}</td>
                <td>{u.email}</td>
                <td>{new Date(u.created_at).toLocaleDateString()}</td>
                <td>
                  <div className="row-actions">
                    <button className="btn btn-secondary btn-sm" onClick={() => openEdit(u)}>Edit</button>
                    <button className="btn btn-danger btn-sm" onClick={() => del(u)}>Delete</button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {modal && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h2>Edit User</h2>
            <div className="form-group">
              <label>Name</label>
              <input type="text" value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
            </div>
            <div className="form-group">
              <label>Email</label>
              <input type="email" value={form.email}
                onChange={e => setForm(f => ({ ...f, email: e.target.value }))} />
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
