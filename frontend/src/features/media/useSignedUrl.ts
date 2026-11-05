// A fresh signed link to a file, fetched on demand and cached. Used for message attachments (useAttachmentUrl)
// and for status photos / videos (useStatusMediaUrl): only the "which link do we ask for" part differs.
//
// A link works for 5 minutes. We keep it for a bit less (staleTime), and if it has expired when the browser
// asks again, TanStack Query fetches a new one. If the picture / player still fails to load (for example the
// link expired while the page was open) the component calls `reload()` ONCE to get a new link.
import { useQuery } from '@tanstack/react-query'
import { useCallback, useRef } from 'react'
import type { SignedUrl } from '../../types/chat'

const SAFETY_MS = 30_000 // use a link only until 30 seconds before it expires
const MAX_STALE_MS = 4 * 60_000

export function useSignedUrl(queryKey: readonly unknown[], fetchLink: () => Promise<SignedUrl>, enabled = true) {
  const query = useQuery({
    queryKey,
    queryFn: fetchLink,
    enabled,
    // Fresh until shortly before the link expires (never longer than 4 minutes).
    staleTime: (q) => {
      const expiresAt = q.state.data?.expiresAt
      if (!expiresAt) return 0
      return Math.max(0, Math.min(MAX_STALE_MS, Date.parse(expiresAt) - Date.now() - SAFETY_MS))
    },
    gcTime: 5 * 60_000,
    retry: 1,
  })

  // The element could not load the file: ask for a new link, but only once per link (no endless loop).
  const reloadedFor = useRef<string | null>(null)
  const { data, refetch } = query
  const reload = useCallback(() => {
    if (!data || reloadedFor.current === data.url) return
    reloadedFor.current = data.url
    void refetch()
  }, [data, refetch])

  return {
    url: query.data?.url ?? null,
    loading: query.isPending && enabled,
    failed: query.isError,
    refetch: query.refetch,
    reload,
  }
}
