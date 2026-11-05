/**
 * The little of Markdown that Claude's words use (a review's summary has "one small table"): pipe tables, lists,
 * headings and paragraphs, as blocks. The phone's `MdBlocks.parse` reads the same and is held to the same cases
 * (`fixtures/web/markdown.json`). Anything else is left as the words were written.
 */
export type MdBlock =
  | { kind: "p"; text: string }
  | { kind: "h"; level: number; text: string }
  | { kind: "list"; ordered: boolean; items: string[] }
  | { kind: "table"; header: string[]; rows: string[][] };

const SEPARATOR = /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/;
const HEADING = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
const BULLET = /^\s*[-*•]\s+(.*)$/;
const NUMBERED = /^\s*\d+[.)]\s+(.*)$/;

/** A table row's cells, its outer pipes dropped. */
function cells(line: string): string[] {
  let row = line.trim();
  if (row.startsWith("|")) row = row.slice(1);
  if (row.endsWith("|")) row = row.slice(0, -1);
  return row.split("|").map((cell) => cell.trim());
}

/** A row the header's width: cut, or filled with empty cells. */
function fitted(row: string[], width: number): string[] {
  return Array.from({ length: width }, (_, i) => row[i] ?? "");
}

export function parse(text: string): MdBlock[] {
  const lines = text.replace(/\r\n?/g, "\n").split("\n");
  const blocks: MdBlock[] = [];
  let para: string[] = [];
  const endPara = () => {
    if (para.length > 0) blocks.push({ kind: "p", text: para.join("\n") });
    para = [];
  };
  // A line past the end reads as empty.
  const at = (n: number) => lines[n] ?? "";
  let i = 0;
  while (i < lines.length) {
    const line = at(i);
    if (line.includes("|") && i + 1 < lines.length && SEPARATOR.test(at(i + 1))) {
      endPara();
      const header = cells(line);
      const rows: string[][] = [];
      i += 2;
      while (i < lines.length && at(i).includes("|") && at(i).trim() !== "") {
        rows.push(fitted(cells(at(i)), header.length));
        i++;
      }
      blocks.push({ kind: "table", header, rows });
      continue;
    }
    const heading = HEADING.exec(line);
    if (heading) {
      endPara();
      blocks.push({ kind: "h", level: (heading[1] ?? "").length, text: heading[2] ?? "" });
      i++;
      continue;
    }
    const bullet = BULLET.exec(line);
    const numbered = bullet ? null : NUMBERED.exec(line);
    if (bullet || numbered) {
      endPara();
      const ordered = numbered !== null;
      const items: string[] = [];
      while (i < lines.length) {
        const item = ordered ? NUMBERED.exec(at(i)) : BULLET.exec(at(i));
        if (!item) break;
        items.push(item[1] ?? "");
        i++;
      }
      blocks.push({ kind: "list", ordered, items });
      continue;
    }
    if (line.trim() === "") endPara();
    else para.push(line);
    i++;
  }
  endPara();
  return blocks;
}

/** A line's **bold** runs, in turn with the plain ones: odd places are bold. */
export function runs(text: string): string[] {
  return text.split(/\*\*(.+?)\*\*/);
}
