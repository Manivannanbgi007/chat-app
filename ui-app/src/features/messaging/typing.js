// Decides WHEN to tell the other person "I am typing".
// - while I type: at most one "typing: true" every 2 seconds
// - when I stop typing for 3 seconds, or send the message: exactly one "typing: false"
export class TypingSender {
  // send(typing: boolean) is called whenever the other side should be told.
  constructor(send, now = Date.now, throttleMs = 2000, idleMs = 3000) {
    this.send = send
    this.now = now
    this.throttleMs = throttleMs
    this.idleMs = idleMs
    this.active = false
    this.lastTrueAt = 0
    this.idleTimer = null
  }

  // Call on every key press.
  keystroke() {
    const time = this.now()
    if (!this.active || time - this.lastTrueAt >= this.throttleMs) {
      this.send(true)
      this.active = true
      this.lastTrueAt = time
    }
    this.restartIdleTimer()
  }

  // Call when the message was sent, the box was emptied, or the user left the conversation.
  stop() {
    this.clearIdleTimer()
    if (this.active) {
      this.active = false
      this.send(false)
    }
  }

  restartIdleTimer() {
    this.clearIdleTimer()
    this.idleTimer = setTimeout(() => this.stop(), this.idleMs)
  }

  clearIdleTimer() {
    if (this.idleTimer) {
      clearTimeout(this.idleTimer)
      this.idleTimer = null
    }
  }
}
