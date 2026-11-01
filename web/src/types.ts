// What the server's views hold (engine's views/ and sync/Protocol.kt): the words are the phone's, made there.

export interface WindowView {
  name: string;
  startMin: number;
  endMin: number;
}

export interface EventChip {
  title: string;
  colour?: number | null;
}

export interface PieceChip {
  label: string;
  minutes: string;
  urgent: boolean;
}

export interface WeekDay {
  date: string;
  weekday: string;
  day: number;
  load?: string | null;
  today: boolean;
  chosen: boolean;
}

export interface NextView {
  label: string;
  first?: string | null;
  then?: string | null;
  taskId?: string | null;
  actions: TaskAction[];
}

export interface DragView {
  key: string;
  minutes: number;
}

export interface TimerView {
  label: string;
  says: string;
  nextAt: number;
  stop?: Action | null;
}

export interface BlockView {
  key: string;
  kind: "piece" | "outside" | "event" | "record" | "busy";
  startMin: number;
  endMin: number;
  title: string;
  times: string;
  state?: "now" | "gone" | "ahead" | null;
  urgent: boolean;
  note?: string | null;
  colour?: number | null;
  free: boolean;
  outcome?: "kept" | "late" | "missed" | null;
  column: number;
  columns: number;
  span: number;
  taskId?: string | null;
  drag?: DragView | null;
}

export interface DayView {
  date: string;
  title: string;
  today: boolean;
  nowMin?: number | null;
  opensAt: number;
  windows: WindowView[];
  allDay: EventChip[];
  blocks: BlockView[];
  unslotted: PieceChip[];
  done: PieceChip[];
  load: string;
  doneLine?: string | null;
  wontFit?: string | null;
  next?: NextView | null;
  week: WeekDay[];
  asOf?: number | null;
  shortest: number;
  canChange: boolean;
  form?: OwnTaskFormView | null;
  timer?: TimerView | null;
}

export interface TaskTile {
  id: string;
  title: string;
  minutes: string;
  due: string;
  overdue: boolean;
  source: string;
  line: string;
  tags: string[];
  sameAs?: string | null;
  same?: Action | null;
  different?: Action | null;
}

export interface TaskGroup {
  title: string;
  tasks: TaskTile[];
}

export interface UnplannedTile {
  id: string;
  source: string;
  title: string;
  why: string;
  minutes: string;
}

export interface SourceLine {
  source: string;
  read: string;
  open: number;
  error?: string | null;
  note?: string | null;
}

export interface WorkView {
  sources: string[];
  byDue: TaskGroup[];
  bySubject: TaskGroup[];
  notPlanned: UnplannedTile[];
  hidden: TaskTile[];
  finished: TaskTile[];
  read: SourceLine[];
  asOf?: number | null;
  canChange: boolean;
  form?: OwnTaskFormView | null;
  photoSide?: number | null;
}

/** A photo read into tasks to check, or why not. */
export interface PhotoRead {
  tasks: OwnTaskEdit[];
  leftOut: string[];
  question?: string | null;
  why?: string | null;
}

export interface KindChoice {
  name: string;
  label: string;
  event: boolean;
}

/** What the form for a task of your own takes: the server's, as every rule is. */
export interface OwnTaskFormView {
  kinds: KindChoice[];
  maxTitle: number;
  maxClass: number;
  maxNotes: number;
  maxSteps: number;
  defaultMinutes: number;
}

export interface DeviceLine {
  id: string;
  name: string;
  createdAt: number;
  lastSeenAt?: number | null;
}

export interface PairingCode {
  code: string;
  expiresAt: number;
}

export interface SettingField {
  name: string;
  label: string;
  kind: string;
  value: unknown;
  min?: number | null;
  max?: number | null;
  overnight: boolean;
  waits: boolean;
  note?: string | null;
  codes: string[];
}

export interface SettingsPage {
  key: string;
  title: string;
  says: string;
  fields: SettingField[];
  note?: string | null;
}

export interface PendingLine {
  id: string;
  line: string;
  cancel?: Action | null;
}

export interface ListLine {
  line: string;
  delete?: Action | null;
}

export interface CredentialField {
  name: string;
  label: string;
  secret: boolean;
  held: boolean;
}

export interface CredentialGroup {
  title: string;
  fields: CredentialField[];
}

export interface SettingsView {
  pages: SettingsPage[];
  pending: PendingLine[];
  armed: boolean;
  delayHours: number;
  parentCode: boolean;
  school: ListLine[];
  rules: ListLine[];
  facts: ListLine[];
  factsRoom?: string | null;
  credentials: CredentialGroup[];
  canChange: boolean;
  efforts: string[];
  effortNote: string;
  calls: CallLine[];
  phoneOnly: string[];
  push?: PushView | null;
}

export interface PushChannel {
  key: string;
  label: string;
}

export interface PushView {
  key: string;
  channels: PushChannel[];
}

export interface CallLine {
  at: number;
  line: string;
  details: string;
}

export interface ResetChoice {
  title: string;
  takes: string;
  button: ChatButton;
}

export interface BackupView {
  backUp: string;
  restore: string;
  fit?: string | null;
  canDownload: boolean;
  lastNightly?: string | null;
  copies: string[];
  resets: ResetChoice[];
  tooBig: string;
  canChange: boolean;
}

export interface SetupLine {
  title: string;
  done?: boolean | null;
  detail: string;
}

export interface SetupView {
  saved: SetupLine[];
  sources: SetupLine[];
  claude: SetupLine;
}

/** A command's action, as the server makes it or as a form fills it in: `type` says which. */
export type Action = { type: string } & Record<string, unknown>;

/** What came of a command. */
export interface Outcome {
  id: string;
  state: "applied" | "dropped" | "refused";
  why?: string | null;
  revision: number;
  /** What takes it back, where only its owner could say. */
  undo?: Action | null;
  /** What's said of it, where its owner's words are the answer. */
  said?: string | null;
}

/** What's asked of a task where it takes the server's rules to make the commands (engine's views/TaskAsks.kt). */
export interface TaskAsk {
  kind: string;
  at?: number | null;
  minutes?: number | null;
  date?: string | null;
  minute?: number | null;
  key?: string | null;
  step?: number | null;
}

/** What came of a [TaskAsk]. */
export interface Asked {
  done: boolean;
  said?: string | null;
}

export interface TaskAction {
  label: string;
  action?: Action | null;
  ask?: TaskAsk | null;
  confirm?: string | null;
  detail?: string | null;
  undo?: Action | null;
}

export interface TaskField {
  kind: string;
  label: string;
  says: string;
  note?: string | null;
  changes: boolean;
  undo?: TaskAction | null;
}

export interface StepLine {
  line: string;
  done: boolean;
  tick?: Action | null;
  timer?: TaskAction | null;
}

export interface NoteLine {
  line: string;
  delete?: TaskAction | null;
}

export interface OwnStep {
  title: string;
  minutes: number;
  from?: number | null;
  dueAt?: number | null;
  where?: string | null;
  id?: string;
  anyOrder?: boolean;
}

export interface OwnTask {
  title: string;
  notes: string;
  kind: string;
  className?: string | null;
  dueAt?: number | null;
  startFrom?: number | null;
  minutes: number;
  steps: OwnStep[];
  where: string;
}

export interface OwnTaskEdit {
  sourceId: string;
  task: OwnTask;
}

export interface MoveDay {
  date: string;
  label: string;
  allowed: boolean;
}

export interface MoveView {
  piece: string;
  days: MoveDay[];
  notes: string[];
}

export interface TaskView {
  id: string;
  title: string;
  detail: string;
  line: string;
  minutes: string;
  due: string;
  overdue: boolean;
  own: boolean;
  tags: string[];
  next?: string | null;
  fields: TaskField[];
  steps: StepLine[];
  planned: string[];
  instructions: string[];
  notes: NoteLine[];
  actions: TaskAction[];
  notable: boolean;
  edit?: OwnTaskEdit | null;
  move?: MoveView | null;
  where?: string | null;
}

export interface ChatQuote {
  id: string;
  who: string;
  line: string;
}

export interface ChatChip {
  words: string;
  send: Action;
}

export interface ChatSourceLine {
  title: string;
  url?: string | null;
}

export interface ChatDid {
  line: string;
  undone: boolean;
  undo?: Action | null;
}

export interface ChatButton {
  label: string;
  action?: Action | null;
  edit?: OwnTaskEdit | null;
  primary: boolean;
  confirm?: string | null;
  detail?: string | null;
  yes?: string | null;
  no?: string | null;
}

export interface ChatCard {
  id: string;
  heading: string;
  title?: string | null;
  line?: string | null;
  steps: string[];
  notes?: string | null;
  lines: string[];
  note?: string | null;
  open?: string | null;
  state?: string | null;
  aside: boolean;
  buttons: ChatButton[];
}

export interface ChatRow {
  id: string;
  yours: boolean;
  at: number;
  time: string;
  day?: string | null;
  texts: string[];
  about?: string | null;
  quote?: ChatQuote | null;
  archived: boolean;
  waiting: boolean;
  mark?: Action | null;
  note?: string | null;
  takeBack?: Action | null;
  tryAgain: boolean;
  chips: ChatChip[];
  ratable: boolean;
  rating: string;
  feedback: string;
  preferred: string;
  feeling: string;
  unsent: string[];
  did: string;
  wrong: string;
  folds: boolean;
  problem: boolean;
  searched?: string | null;
  sources: ChatSourceLine[];
  shown: ChatCard[];
  cards: ChatCard[];
  lines: ChatDid[];
  said?: string | null;
  opens?: string | null;
}

export interface ChangeRow {
  id: string;
  about: string;
  words: string;
  says?: string | null;
  reading: boolean;
  problem: boolean;
  heading?: string | null;
  lines: string[];
  note?: string | null;
  rewrite: boolean;
  buttons: ChatButton[];
}

export interface ChatView {
  name: string;
  rows: ChatRow[];
  earlier: number;
  page: number;
  more: boolean;
  firstUnread?: string | null;
  unread: number;
  newestAt?: number | null;
  typing: boolean;
  sentAt?: number | null;
  cancel?: Action | null;
  claudeOff?: string | null;
  intro?: string | null;
  examples: string[];
  chips: ChatChip[];
  offer?: ChatQuote | null;
  quiet?: string | null;
  markAll?: Action | null;
  clear?: ChatButton | null;
  toCheck: ChangeRow[];
  inUse: ChangeRow[];
  canChange: boolean;
  form?: OwnTaskFormView | null;
  maxText: number;
  maxFeedback: number;
  readAfterMs: number;
  undoMs: number;
  cancelAfterMs: number;
}

export interface NoticesView {
  unread: number;
  setup: boolean;
}

export interface PeriodChoice {
  key: string;
  label: string;
  chosen: boolean;
}

export interface DayBar {
  label: string;
  today: boolean;
  share: number;
  line: string;
  quiet: boolean;
}

export interface HeatCell {
  kept: number;
  missed: number;
  share: number;
  missedMore: boolean;
}

export interface HeatRow {
  day: string;
  cells: HeatCell[];
}

export interface StatsView {
  periods: PeriodChoice[];
  heading: string;
  tiles: { label: string; value: string; note: string; warn: boolean }[];
  days: DayBar[];
  foldAfter: number;
  subjects: string[];
  heat: HeatRow[];
  heatHours: number[];
  heatNote: string;
  hours?: ChatButton | null;
  learned: { line: string; forget?: ChatButton | null }[];
  nothingLearned?: string | null;
  inFull: string[];
  canChange: boolean;
}

export interface WeekView {
  week: string;
  done: boolean;
  doneWords: string;
  feel: string;
  feelNote: string;
  questions: { name: string; label: string }[];
  save: string;
  latest?: { heading: string; lines: string[]; by: string } | null;
  canChange: boolean;
}

export interface ReviewLine {
  text: string;
  undo?: Action | null;
}

export interface ReviewCard {
  id: number;
  week: string;
  by: string;
  lines: string[];
  changes: ReviewLine[];
  tidy?: ReviewLine | null;
  summary?: string | null;
  mailto?: string | null;
  checkIn?: string | null;
}

export interface ReviewsView {
  cards: ReviewCard[];
  none?: string | null;
}
