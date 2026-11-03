const BASE = '/api'

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options?.headers ?? {}) },
    ...options,
  })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`${res.status}: ${text}`)
  }
  if (res.status === 204) return undefined as T
  return res.json()
}

export const api = {
  // Users
  getUsers: () => request<import('./types').User[]>('/users/'),
  updateUser: (id: number, body: Partial<{ name: string; email: string }>) =>
    request(`/users/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteUser: (id: number) => request(`/users/${id}`, { method: 'DELETE' }),

  // Devices
  getDevices: () => request<import('./types').Device[]>('/devices/'),
  updateDevice: (deviceId: string, body: object) =>
    request(`/devices/${deviceId}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteDevice: (deviceId: string) =>
    request(`/devices/${deviceId}`, { method: 'DELETE' }),

  // Tracks
  getTracks: () => request<import('./types').Track[]>('/tracks/'),
  createTrack: (body: object) =>
    request('/tracks/', { method: 'POST', body: JSON.stringify(body) }),
  updateTrack: (id: number, body: object) =>
    request(`/tracks/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteTrack: (id: number) => request(`/tracks/${id}`, { method: 'DELETE' }),

  // OSM discovery / ingest
  discoverTracks: (body: { lat: number; lon: number; radius_m?: number }) =>
    request<{ token: string; candidates: import('./types').OsmCandidate[] }>(
      '/tracks/discover',
      { method: 'POST', body: JSON.stringify(body) },
    ),
  ingestTracks: (token: string, selectedIndices: number[]) =>
    request<{ ingested: import('./types').Track[]; skipped: { name: string; reason: string }[] }>(
      '/tracks/ingest',
      { method: 'POST', body: JSON.stringify({ token, selected_indices: selectedIndices }) },
    ),
}
