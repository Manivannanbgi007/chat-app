// A soft "ring ring" for incoming calls, made with the Web Audio API (no sound file to download).
// Browsers only allow sound after the user has interacted with the page, and some have no Web Audio at all:
// in every such case the ring tone simply stays silent. It must never break a call.

let context = null
let timer = null

const RING_EVERY_MS = 2500

function tone(ctx, startAt) {
  const oscillator = ctx.createOscillator()
  const gain = ctx.createGain()
  oscillator.frequency.value = 440
  gain.gain.setValueAtTime(0.0001, startAt)
  gain.gain.exponentialRampToValueAtTime(0.08, startAt + 0.02) // quiet, and without a click
  gain.gain.exponentialRampToValueAtTime(0.0001, startAt + 0.4)
  oscillator.connect(gain)
  gain.connect(ctx.destination)
  oscillator.start(startAt)
  oscillator.stop(startAt + 0.45)
}

function ring() {
  try {
    context ??= new AudioContext()
    if (context.state === 'suspended') context.resume().catch(() => undefined)
    const now = context.currentTime
    tone(context, now)
    tone(context, now + 0.5)
  } catch {
    // no sound allowed here: stay silent
  }
}

export const ringtone = {
  start() {
    if (timer || typeof AudioContext === 'undefined') return
    ring()
    timer = setInterval(ring, RING_EVERY_MS)
  },
  stop() {
    if (timer) clearInterval(timer)
    timer = null
  },
}
