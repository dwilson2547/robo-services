import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Pipeline } from '../types'

type PipelineForm = { name: string; description: string; default_config: string }
const emptyForm: PipelineForm = { name: '', description: '', default_config: '' }

export default function PipelinesPage() {
  const [pipelines, setPipelines] = useState<Pipeline[]>([])
  const [error, setError] = useState('')
  const [modal, setModal] = useState<'create' | 'edit' | null>(null)
  const [editing, setEditing] = useState<Pipeline | null>(null)
  const [form, setForm] = useState<PipelineForm>(emptyForm)
  const [formError, setFormError] = useState('')

  const load = () => api.getPipelines().then(setPipelines).catch(e => setError(String(e)))
  useEffect(() => { load() }, [])

  const openCreate = () => { setForm(emptyForm); setEditing(null); setFormError(''); setModal('create') }
  const openEdit = (p: Pipeline) => {
    setForm({
      name: p.name,
      description: p.description ?? '',
      default_config: p.default_config ? JSON.stringify(p.default_config, null, 2) : '',
    })
    setEditing(p); setFormError(''); setModal('edit')
  }
  const close = () => setModal(null)

  const save = async () => {
    setFormError('')
    let parsed: object | undefined
    if (form.default_config.trim()) {
      try { parsed = JSON.parse(form.default_config) } catch { setFormError('default_config is not valid JSON'); return }
    }
    try {
      if (modal === 'create') {
        await api.createPipeline({ name: form.name, description: form.description || undefined, default_config: parsed })
      } else if (editing) {
        await api.updatePipeline(editing.name, { description: form.description || undefined, default_config: parsed })
      }
      close(); load()
    } catch (e) { setFormError(String(e)) }
  }

  const del = async (p: Pipeline) => {
    if (!confirm(`Delete pipeline "${p.name}"? This will remove all device assignments.`)) return
    try { await api.deletePipeline(p.name); load() } catch (e) { setError(String(e)) }
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2>Pipelines</h2>
        <button onClick={openCreate}>+ New Pipeline</button>
      </div>
      {error && <div className="error">{error}</div>}
      <table>
        <thead>
          <tr><th>Name</th><th>Description</th><th>Actions</th></tr>
        </thead>
        <tbody>
          {pipelines.map(p => (
            <tr key={p.id}>
              <td><code>{p.name}</code></td>
              <td>{p.description ?? <span style={{ color: '#888' }}>—</span>}</td>
              <td>
                <button onClick={() => openEdit(p)}>Edit</button>
                {' '}
                <button className="danger" onClick={() => del(p)}>Delete</button>
              </td>
            </tr>
          ))}
          {pipelines.length === 0 && (
            <tr><td colSpan={3} style={{ textAlign: 'center', color: '#888' }}>No pipelines registered</td></tr>
          )}
        </tbody>
      </table>

      {modal && (
        <div className="modal-overlay" onClick={close}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h3>{modal === 'create' ? 'New Pipeline' : `Edit: ${editing?.name}`}</h3>
            {modal === 'create' && (
              <label>
                Name
                <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="e.g. speed" />
              </label>
            )}
            <label>
              Description
              <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
            </label>
            <label>
              Default Config (JSON)
              <textarea
                rows={6}
                value={form.default_config}
                onChange={e => setForm(f => ({ ...f, default_config: e.target.value }))}
                placeholder="{}"
              />
            </label>
            {formError && <div className="error">{formError}</div>}
            <div className="modal-actions">
              <button onClick={save}>{modal === 'create' ? 'Create' : 'Save'}</button>
              <button onClick={close}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
