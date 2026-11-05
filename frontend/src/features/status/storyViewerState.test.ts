import { profile } from '../../test/chatFixtures'
import { makeMyStatus, makePhotoStatus, makeStatus, makeVideoStatus } from '../../test/statusFixtures'
import { STATUS_DISPLAY_MS } from './statusRules'
import {
  currentFraction,
  currentStatus,
  initialViewerState,
  viewerReducer,
  type ViewerAction,
  type ViewerGroup,
  type ViewerState,
} from './storyViewerState'

function group(name: string, statuses = [makeStatus(), makeStatus()], mine = false): ViewerGroup {
  return { owner: profile(`u-${name}`, name), statuses, mine }
}

function run(state: ViewerState, ...actions: ViewerAction[]): ViewerState {
  return actions.reduce(viewerReducer, state)
}

describe('story viewer state', () => {
  it('starts where it is told (kept inside the lists) and shows texts at once', () => {
    const state = initialViewerState([group('Asha'), group('Ravi')], 1, 5)
    expect(state.group).toBe(1)
    expect(state.index).toBe(1)
    expect(state.ready).toBe(true)
    expect(initialViewerState([], 0, 0).closed).toBe(true)
  })

  it('moves on after 5 seconds of a text, then to the next person, then closes', () => {
    const second = [makeStatus({ viewedByMe: true }), makeStatus()]
    let state = initialViewerState([group('Asha', [makeStatus()]), group('Ravi', second)], 0, 0)

    state = run(state, { type: 'TICK', ms: STATUS_DISPLAY_MS - 100 })
    expect(currentFraction(state)).toBeCloseTo(0.98)
    state = run(state, { type: 'TICK', ms: 100 })
    // Ravi: starts at his first status I have not seen
    expect([state.group, state.index, state.elapsed]).toEqual([1, 1, 0])

    state = run(state, { type: 'NEXT' })
    expect(state.closed).toBe(true)
    expect(run(state, { type: 'PREVIOUS' })).toBe(state) // closed stays closed
  })

  it('goes back within a person, then to the previous person, and restarts the very first one', () => {
    let state = initialViewerState([group('Asha'), group('Ravi')], 1, 1)
    state = run(state, { type: 'PREVIOUS' })
    expect([state.group, state.index]).toEqual([1, 0])
    state = run(state, { type: 'PREVIOUS' })
    expect([state.group, state.index]).toEqual([0, 0])
    state = run(state, { type: 'TICK', ms: 1000 }, { type: 'PREVIOUS' })
    expect([state.group, state.index, state.elapsed]).toEqual([0, 0, 0])
  })

  it('waits for a photo to load before the timer runs, and shows a failed one for 5 s too', () => {
    const photo = makePhotoStatus()
    let state = initialViewerState([group('Asha', [photo, makeStatus()])], 0, 0)
    expect(state.ready).toBe(false)
    state = run(state, { type: 'TICK', ms: 10_000 })
    expect(state.index).toBe(0) // nothing happens while the photo loads

    state = run(state, { type: 'READY', statusId: 'other' }) // a late answer for another status is ignored
    expect(state.ready).toBe(false)
    state = run(state, { type: 'FAILED', statusId: photo.id })
    expect(run(state, { type: 'FAILED', statusId: photo.id })).toBe(state) // same object: no extra render
    state = run(state, { type: 'TICK', ms: STATUS_DISPLAY_MS })
    expect(state.index).toBe(1)
  })

  it('lets a video run until it ends (the timer does not move it) and fills its bar from the player', () => {
    const video = makeVideoStatus()
    let state = initialViewerState([group('Asha', [video, makeStatus()])], 0, 0)
    state = run(state, { type: 'READY', statusId: video.id }, { type: 'TICK', ms: 60_000 })
    expect(state.index).toBe(0)
    state = run(state, { type: 'VIDEO_PROGRESS', statusId: video.id, fraction: 0.5 })
    expect(currentFraction(state)).toBe(0.5)
    state = run(state, { type: 'NEXT' }) // what the player's "ended" sends
    expect(state.index).toBe(1)
  })

  it('after a delete stays on the same place, moves to the next person, and closes when nothing is left', () => {
    const [a, b] = [makeMyStatus(), makeMyStatus()]
    let state = initialViewerState([group('Me', [a, b], true)], 0, 0)

    state = run(state, { type: 'REMOVE', statusId: a.id })
    expect(currentStatus(state)?.id).toBe(b.id)
    state = run(state, { type: 'REMOVE', statusId: b.id })
    expect(state.closed).toBe(true)

    const only = makeStatus()
    const later = makeStatus()
    let feed = initialViewerState([group('Asha', [only]), group('Ravi', [later])], 0, 0)
    feed = run(feed, { type: 'REMOVE', statusId: only.id })
    expect(currentStatus(feed)?.id).toBe(later.id)
  })

  it('closes on CLOSE', () => {
    expect(run(initialViewerState([group('Asha')], 0, 0), { type: 'CLOSE' }).closed).toBe(true)
  })
})
