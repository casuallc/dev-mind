import { api } from '../../shared/api/client'
import type { ChangelogEntryView, ChangelogPageView, ChangelogQuery } from './types'

function qs(params: ChangelogQuery): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') q.set(k, String(v))
  }
  return q.toString()
}

export const listChangelog = (params: ChangelogQuery = {}) =>
  api.get<ChangelogPageView>(`/changelog?${qs(params)}`)

export const getChangelog = (version: string) =>
  api.get<ChangelogEntryView>(`/changelog/${encodeURIComponent(version)}`)
