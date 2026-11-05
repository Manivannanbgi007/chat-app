// Where the story viewer is and what it shows, as a pure reducer (no React, no timers: storyViewerState.test.ts).
//
// The component sends the actions: TICK every 100 ms while a text or photo is on screen (and not paused),
// NEXT / PREVIOUS from taps and arrow keys, VIDEO_PROGRESS / NEXT from the video player, READY when a photo or video
// can be shown, FAILED when it cannot be loaded, REMOVE after I deleted one of my statuses, CLOSE from Escape / X.
import type { PublicProfile } from '../../types/user'
import type { MyStatus, Status } from '../../types/status'
import { firstUnviewedIndex, STATUS_DISPLAY_MS } from './statusRules'

// One person's statuses in the viewer. For my own statuses `mine` is true (with view counts).
export interface ViewerGroup {
  owner: PublicProfile
  statuses: (Status | MyStatus)[]
  mine: boolean
}

export interface ViewerState {
  groups: ViewerGroup[]
  group: number // index in groups
  index: number // index in groups[group].statuses
  elapsed: number // ms the current text / photo has been on screen
  videoFraction: number // 0..1 of the current video
  ready: boolean // the current status can be shown (text at once; photo / video once loaded)
  failed: boolean // the photo / video could not be loaded (it then stays 5 s like a text)
  closed: boolean
}

export type ViewerAction =
  | { type: 'NEXT' }
  | { type: 'PREVIOUS' }
  | { type: 'TICK'; ms: number }
  | { type: 'VIDEO_PROGRESS'; statusId: string; fraction: number }
  | { type: 'READY'; statusId: string }
  | { type: 'FAILED'; statusId: string }
  | { type: 'REMOVE'; statusId: string }
  | { type: 'CLOSE' }

export function currentStatus(state: ViewerState): Status | MyStatus | null {
  return state.groups[state.group]?.statuses[state.index] ?? null
}

// Moves to a status: the timer starts again and a photo / video must load first.
function show(state: ViewerState, group: number, index: number): ViewerState {
  const status = state.groups[group]?.statuses[index]
  return { ...state, group, index, elapsed: 0, videoFraction: 0, ready: status?.type === 'TEXT', failed: false }
}

export function initialViewerState(groups: ViewerGroup[], group: number, index: number): ViewerState {
  const base: ViewerState = { groups, group: 0, index: 0, elapsed: 0, videoFraction: 0, ready: false, failed: false, closed: false }
  if (groups.length === 0) return { ...base, closed: true }
  const safeGroup = Math.min(Math.max(group, 0), groups.length - 1)
  const safeIndex = Math.min(Math.max(index, 0), groups[safeGroup].statuses.length - 1)
  return show(base, safeGroup, safeIndex)
}

function next(state: ViewerState): ViewerState {
  const group = state.groups[state.group]
  if (state.index < group.statuses.length - 1) return show(state, state.group, state.index + 1)
  if (state.group < state.groups.length - 1) {
    // the next person: start at the first status I have not seen yet (my own: at the first)
    const nextGroup = state.groups[state.group + 1]
    return show(state, state.group + 1, nextGroup.mine ? 0 : firstUnviewedIndex(nextGroup.statuses))
  }
  return { ...state, closed: true } // that was the last one
}

function previous(state: ViewerState): ViewerState {
  if (state.index > 0) return show(state, state.group, state.index - 1)
  if (state.group > 0) return show(state, state.group - 1, 0)
  return show(state, state.group, state.index) // already at the very first: start it again
}

function remove(state: ViewerState, statusId: string): ViewerState {
  const groups = state.groups
    .map((group) => ({ ...group, statuses: group.statuses.filter((status) => status.id !== statusId) }))
    .filter((group) => group.statuses.length > 0)
  if (groups.length === 0) return { ...state, groups, closed: true }
  // Stay on the same place: the status after the deleted one moves into it. If the deleted status was the
  // person's only one, the next person moves into this place (or the previous one, if it was the last person).
  const removedWholeGroup = groups.length < state.groups.length
  const group = Math.min(state.group, groups.length - 1)
  const index = removedWholeGroup ? 0 : Math.min(state.index, groups[group].statuses.length - 1)
  return show({ ...state, groups }, group, index)
}

export function viewerReducer(state: ViewerState, action: ViewerAction): ViewerState {
  if (state.closed) return state
  const current = currentStatus(state)
  switch (action.type) {
    case 'NEXT':
      return next(state)
    case 'PREVIOUS':
      return previous(state)
    case 'TICK': {
      if (!state.ready || (current?.type === 'VIDEO' && !state.failed)) return state
      const elapsed = state.elapsed + action.ms
      return elapsed >= STATUS_DISPLAY_MS ? next(state) : { ...state, elapsed }
    }
    case 'VIDEO_PROGRESS':
      return current?.id === action.statusId ? { ...state, videoFraction: Math.min(1, Math.max(0, action.fraction)) } : state
    case 'READY':
      return current?.id === action.statusId && !state.ready ? { ...state, ready: true } : state
    case 'FAILED':
      // the same object when nothing changes, so React does not render again
      return current?.id === action.statusId && !state.failed ? { ...state, ready: true, failed: true } : state
    case 'REMOVE':
      return remove(state, action.statusId)
    case 'CLOSE':
      return { ...state, closed: true }
  }
}

// How far the bar of the current status is filled (0..1).
export function currentFraction(state: ViewerState): number {
  const status = currentStatus(state)
  if (!status) return 0
  if (status.type === 'VIDEO' && !state.failed) return state.videoFraction
  return Math.min(1, state.elapsed / STATUS_DISPLAY_MS)
}
