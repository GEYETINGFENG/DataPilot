import axios from 'axios'

export interface Column {
  name: string
  type: string
  primaryKey: boolean
  comment: string
}

export interface TableSchema {
  name: string
  comment: string
  columns: Column[]
}

export interface QueryResult {
  columns: string[]
  rows: unknown[][]
  truncated: boolean
  elapsedMs: number
}

export interface QueryResponse {
  /** The statement that actually ran, after the server-side guard applied its row limit. */
  sql: string
  result: QueryResult
}

/** Errors come back as RFC 9457 problem details. */
export function problemDetail(error: unknown): string {
  if (axios.isAxiosError(error) && error.response?.data?.detail) {
    return error.response.data.detail
  }
  return error instanceof Error ? error.message : String(error)
}

export async function listTables(): Promise<TableSchema[]> {
  const { data } = await axios.get<TableSchema[]>('/api/warehouse/tables')
  return data
}

export async function runQuery(sql: string): Promise<QueryResponse> {
  const { data } = await axios.post<QueryResponse>('/api/warehouse/query', { sql })
  return data
}
