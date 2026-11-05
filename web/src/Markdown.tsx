import { Fragment } from "react";
import { parse, runs } from "./mdBlocks";

function Inline({ text }: { text: string }) {
  return (
    <>
      {runs(text).map((run, i) => (i % 2 === 1 ? <strong key={i}>{run}</strong> : <Fragment key={i}>{run}</Fragment>))}
    </>
  );
}

/** Words as Markdown's blocks lay them out (`mdBlocks.ts`), drawn as elements: nothing in them is read as HTML. */
export function Markdown({ text, className }: { text: string; className?: string }) {
  const blocks = parse(text);
  return (
    <div className={className ? `md ${className}` : "md"}>
      {blocks.map((block, i) =>
        block.kind === "table" ? (
          <table key={i}>
            <thead>
              <tr>
                {block.header.map((cell, c) => (
                  <th key={c} scope="col">
                    <Inline text={cell} />
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((row, r) => (
                <tr key={r}>
                  {row.map((cell, c) => (
                    <td key={c}>
                      <Inline text={cell} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        ) : block.kind === "list" ? (
          block.ordered ? (
            <ol key={i} start={block.start}>
              {block.items.map((item, n) => (
                <li key={n}>
                  <Inline text={item} />
                </li>
              ))}
            </ol>
          ) : (
            <ul key={i}>
              {block.items.map((item, n) => (
                <li key={n}>
                  <Inline text={item} />
                </li>
              ))}
            </ul>
          )
        ) : block.kind === "h" ? (
          block.level <= 2 ? (
            <h3 key={i}>
              <Inline text={block.text} />
            </h3>
          ) : (
            <h4 key={i}>
              <Inline text={block.text} />
            </h4>
          )
        ) : (
          <p key={i}>
            <Inline text={block.text} />
          </p>
        ),
      )}
    </div>
  );
}
