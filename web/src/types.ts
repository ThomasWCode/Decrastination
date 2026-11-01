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

/** A command's action, as the server makes it or as a form fills it in: `type` says which. */
export type Action = { type: string } & Record<string, unknown>;

/** What came of a command. */
export interface Outcome {
  id: string;
  state: "applied" | "dropped";
  why?: string | null;
  revision: number;
}

/** What's asked of a task where it takes the server's rules to make the commands (engine's views/TaskAsks.kt). */
export interface TaskAsk {
  kind: string;
  at?: number | null;
  minutes?: number | null;
  date?: string | null;
  minute?: number | null;
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
