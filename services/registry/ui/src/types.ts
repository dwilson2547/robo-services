export interface User {
  id: number
  name: string
  email: string
  created_at: string
}

export interface Device {
  id: number
  device_id: string
  display_name: string
  hardware_spec: string | null
  notes: string | null
  user_id: number | null
  owner_email: string | null
  created_at: string
  last_seen_at: string | null
  source: string
}

export interface Track {
  id: number
  name: string
  country: string | null
  surface_type: string | null
  source: string
  geometry: Record<string, unknown> | null
  start_line: Record<string, unknown> | null
  osm_relation_id: number | null
  osm_way_ids: number[] | null
  notes: string | null
  created_at: string
}

export interface OsmCandidate {
  name: string
  osm_relation_id: number | null
  osm_way_ids: number[] | null
  geometry: Record<string, unknown>
  geometry_type: 'polygon' | 'linestring'
  already_imported: boolean
}
