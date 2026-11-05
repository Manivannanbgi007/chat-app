import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Camera, CircleDashed, Loader2, Lock, Pencil, Plus } from 'lucide-react'
import { useCallback, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Avatar from '../../components/common/Avatar'
import MediaStatusComposer from '../../components/status/MediaStatusComposer'
import PageHeader from '../../components/common/PageHeader'
import StatusGroupRow from '../../components/status/StatusGroupRow'
import StatusRing from '../../components/status/StatusRing'
import StoryViewer from '../../components/status/StoryViewer'
import TextStatusComposer from '../../components/status/TextStatusComposer'
import { acceptAttribute, validateFile } from '../../features/media/mediaRules'
import {
  FEED_POLL_MS,
  firstUnviewedIndex,
  MY_STATUSES_KEY,
  STATUS_FEED_KEY,
  formatStatusTime,
  markViewedInFeed,
  splitFeed,
  statusKindOf,
} from '../../features/status/statusRules'
import type { ViewerGroup } from '../../features/status/storyViewerState'
import { toAppError } from '../../services/api/appError'
import { getMyStatuses, getStatusFeed } from '../../services/api/statusApi'
import { useAuthStore } from '../../store/authStore'
import type { MyStatus, Status, StatusGroup } from '../../types/status'
import type { PublicProfile } from '../../types/user'

interface OpenViewer {
  groups: ViewerGroup[]
  group: number
  index: number
}

// /status: my status, then "Recent updates" (people with something I have not seen) and "Viewed updates".
// The lists are asked when the page opens and every 30 seconds while it stays open (no live push for statuses).
// Viewing, posting and deleting update the lists at once, without waiting for the next poll.
export default function StatusPage() {
  const queryClient = useQueryClient()
  const user = useAuthStore((state) => state.user)
  const feedQuery = useQuery({ queryKey: STATUS_FEED_KEY, queryFn: getStatusFeed, refetchInterval: FEED_POLL_MS })
  const mineQuery = useQuery({ queryKey: MY_STATUSES_KEY, queryFn: getMyStatuses, refetchInterval: FEED_POLL_MS })

  const [composer, setComposer] = useState<null | { type: 'TEXT' } | { type: 'MEDIA'; file: File; kind: 'IMAGE' | 'VIDEO' }>(
    null,
  )
  const [viewer, setViewer] = useState<OpenViewer | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const mine = mineQuery.data ?? []
  const { recent, viewed } = splitFeed(feedQuery.data ?? [])
  const me: PublicProfile = {
    id: user?.id ?? '',
    username: user?.username ?? '',
    displayName: user?.displayName ?? 'Me',
    profileImageUrl: null,
    about: null,
  }

  // ---- viewing ----

  function openFeed(sectionGroups: StatusGroup[], position: number) {
    const groups: ViewerGroup[] = sectionGroups.map((group) => ({ owner: group.owner, statuses: group.statuses, mine: false }))
    setViewer({ groups, group: position, index: firstUnviewedIndex(sectionGroups[position].statuses) })
  }

  function openMine() {
    if (mine.length === 0) return
    setViewer({ groups: [{ owner: me, statuses: mine, mine: true }], group: 0, index: 0 })
  }

  // Seen: the ring segment turns grey at once.
  const handleViewed = useCallback(
    (statusId: string) => {
      queryClient.setQueryData<StatusGroup[]>(STATUS_FEED_KEY, (groups) => (groups ? markViewedInFeed(groups, statusId) : groups))
    },
    [queryClient],
  )

  const handleDeleted = useCallback(
    (statusId: string) => {
      queryClient.setQueryData<MyStatus[]>(MY_STATUSES_KEY, (list) => list?.filter((status) => status.id !== statusId))
      void queryClient.invalidateQueries({ queryKey: MY_STATUSES_KEY })
    },
    [queryClient],
  )

  const closeViewer = useCallback(() => setViewer(null), [])

  // ---- posting ----

  function pickFile() {
    setFileError(null)
    fileInputRef.current?.click()
  }

  function handleFile(file: File | undefined) {
    if (fileInputRef.current) fileInputRef.current.value = '' // the same file can be picked again later
    if (!file) return
    const kind = statusKindOf(file)
    if (!kind) {
      setFileError('Only photos and videos can be posted as a status.')
      return
    }
    const problem = validateFile(file, kind)
    if (problem) {
      setFileError(problem)
      return
    }
    setComposer({ type: 'MEDIA', file, kind })
  }

  function handlePosted(status: Status) {
    setComposer(null)
    queryClient.setQueryData<MyStatus[]>(MY_STATUSES_KEY, (list) =>
      list && !list.some((existing) => existing.id === status.id) ? [...list, { ...status, viewCount: 0 }] : list,
    )
    void queryClient.invalidateQueries({ queryKey: MY_STATUSES_KEY })
  }

  const latestMine = mine[mine.length - 1]
  const loadError = feedQuery.error ?? mineQuery.error
  const nothingToShow = feedQuery.isSuccess && recent.length === 0 && viewed.length === 0

  return (
    <section>
      <PageHeader
        title="Status"
        subtitle="Updates from your contacts disappear after 24 hours."
        action={
          <Link
            to="/settings#status-privacy"
            className="flex items-center gap-2 rounded-xl border border-gray-200 bg-white px-4 py-2 text-sm font-medium shadow-sm hover:border-brand-300 hover:text-brand-700 dark:border-gray-700 dark:bg-gray-900 dark:hover:text-brand-300"
          >
            <Lock size={14} /> Status privacy
          </Link>
        }
      />

      <div className="grid items-start gap-6 xl:grid-cols-[26rem_1fr]">
        {/* My status */}
        <div data-testid="my-status" className="card flex items-center gap-3 p-4">
          {mine.length > 0 ? (
            <button
              type="button"
              onClick={openMine}
              aria-label="View my status"
              className="flex min-w-0 flex-1 items-center gap-3 rounded-xl p-1 text-left hover:bg-gray-50 dark:hover:bg-gray-800/70"
            >
              <StatusRing name={me.displayName} segments={mine.map(() => true)} />
              <span className="min-w-0">
                <span className="block font-semibold">My status</span>
                <span className="block text-sm text-gray-500 dark:text-gray-400">
                  {mine.length === 1 ? '1 update' : `${mine.length} updates`} · {formatStatusTime(latestMine.createdAt)}
                  {' · '}
                  <span data-testid="my-view-count">
                    {latestMine.viewCount === 1 ? '1 view' : `${latestMine.viewCount} views`}
                  </span>
                </span>
              </span>
            </button>
          ) : (
            <div className="flex min-w-0 flex-1 items-center gap-3">
              <span className="relative">
                <Avatar name={me.displayName} size={48} />
                <span className="btn-brand absolute -bottom-1 -right-1 rounded-full p-0.5 text-white">
                  <Plus size={14} />
                </span>
              </span>
              <span className="min-w-0">
                <span className="block font-semibold">My status</span>
                <span className="block text-sm text-gray-500 dark:text-gray-400">
                  {mineQuery.isPending ? 'Loading...' : 'Add a status update. It disappears after 24 hours.'}
                </span>
              </span>
            </div>
          )}
          <button
            type="button"
            onClick={() => setComposer({ type: 'TEXT' })}
            aria-label="Add text status"
            title="Add text status"
            className="rounded-xl bg-brand-50 p-2.5 text-brand-600 hover:bg-brand-100 dark:bg-brand-950/60 dark:hover:bg-brand-900/60"
          >
            <Pencil size={20} />
          </button>
          <button
            type="button"
            onClick={pickFile}
            aria-label="Add photo or video status"
            title="Add photo or video status"
            className="rounded-xl bg-brand-50 p-2.5 text-brand-600 hover:bg-brand-100 dark:bg-brand-950/60 dark:hover:bg-brand-900/60"
          >
            <Camera size={20} />
          </button>
          <input
            ref={fileInputRef}
            type="file"
            accept={`${acceptAttribute('IMAGE')},${acceptAttribute('VIDEO')}`}
            aria-label="Photo or video file"
            className="hidden"
            onChange={(event) => handleFile(event.target.files?.[0])}
          />
        </div>

        {/* The updates of my contacts */}
        <div className="min-w-0">
          {fileError && (
            <p role="alert" className="mb-3 text-sm text-red-600">
              {fileError}
            </p>
          )}
          {loadError && (
            <p role="alert" className="mb-3 text-sm text-red-600">
              {toAppError(loadError).message}
            </p>
          )}
          {feedQuery.isPending && (
            <p className="flex items-center gap-2 py-4 text-sm">
              <Loader2 className="animate-spin" size={18} /> Loading status updates...
            </p>
          )}

          {recent.length > 0 && (
            <div data-testid="recent-updates" className="mb-8">
              <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">Recent updates</h2>
              <ul aria-label="Recent updates" className="stagger grid gap-3 md:grid-cols-2 2xl:grid-cols-3">
                {recent.map((group, position) => (
                  <StatusGroupRow key={group.owner.id} group={group} onOpen={() => openFeed(recent, position)} />
                ))}
              </ul>
            </div>
          )}

          {viewed.length > 0 && (
            <div data-testid="viewed-updates">
              <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">Viewed updates</h2>
              <ul aria-label="Viewed updates" className="stagger grid gap-3 opacity-90 md:grid-cols-2 2xl:grid-cols-3">
                {viewed.map((group, position) => (
                  <StatusGroupRow key={group.owner.id} group={group} onOpen={() => openFeed(viewed, position)} />
                ))}
              </ul>
            </div>
          )}

          {nothingToShow && (
            <p className="card flex flex-col items-center gap-3 px-4 py-16 text-center text-sm text-gray-500">
              <CircleDashed size={36} />
              No status updates from your contacts yet. Status updates disappear after 24 hours.
            </p>
          )}
        </div>
      </div>

      {composer?.type === 'TEXT' && <TextStatusComposer onPosted={handlePosted} onClose={() => setComposer(null)} />}
      {composer?.type === 'MEDIA' && (
        <MediaStatusComposer
          file={composer.file}
          kind={composer.kind}
          onPosted={handlePosted}
          onClose={() => setComposer(null)}
        />
      )}
      {viewer && (
        <StoryViewer
          groups={viewer.groups}
          startGroup={viewer.group}
          startIndex={viewer.index}
          onViewed={handleViewed}
          onDeleted={handleDeleted}
          onClose={closeViewer}
        />
      )}
    </section>
  )
}
