/**
 * A visit to the chat (docs/spec/felix.md §The chat): seen once it's been in front [afterMs], unbroken, or by a message of yours sent.
 * Only a seen visit reads what it showed: one passed through leaves it unread.
 */
export class Visit {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private seen = false;

  constructor(private readonly afterMs: number) {}

  /** In front: the time counts from now, unless it's seen already or counting. */
  shown() {
    if (this.seen || this.timer !== null) return;
    this.timer = setTimeout(() => {
      this.seen = true;
      this.timer = null;
    }, this.afterMs);
  }

  /** A message of yours sent: seen at once, as you've answered. */
  sent() {
    this.stop();
    this.seen = true;
  }

  /** Gone from the front: whether it was seen. The next visit starts afresh. */
  left(): boolean {
    this.stop();
    const was = this.seen;
    this.seen = false;
    return was;
  }

  private stop() {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }
}
