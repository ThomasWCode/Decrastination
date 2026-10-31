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

export interface TaskAction {
  label: string;
  action: Action;
  confirm?: string | null;
  detail?: string | null;
}

export interface StepLine {
  line: string;
  done: boolean;
  tick?: Action | null;
}

export interface NoteLine {
  line: string;
  delete: Action;
  every?: string | null;
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
  steps: StepLine[];
  planned: string[];
  instructions: string[];
  notes: NoteLine[];
  actions: TaskAction[];
  notable: boolean;
}
