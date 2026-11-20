import { useRef, useState } from "react";
import { ApiError, checkPhotos } from "./api";
import { shrunk } from "./PhotoTasks";
import type { PhotoCheckTarget } from "./types";

/** [blob] as base64, as a check's page is sent. */
function base64(blob: Blob): Promise<string> {
  return new Promise((done, failed) => {
    const reader = new FileReader();
    reader.onload = () => done(String(reader.result).replace(/^data:[^,]*,/, ""));
    reader.onerror = () => failed(reader.error ?? new Error("Unreadable"));
    reader.readAsDataURL(blob);
  });
}

/**
 * What [target] checks, as a key: pictures chosen for one piece are never sent against another the sheet comes to show in its place
 * (its next, once one's done), as a check keyed by it starts again with nothing chosen.
 */
export function targetKey(target: PhotoCheckTarget): string {
  return [target.taskId, target.key, target.step ?? "", target.record?.key ?? "", target.record?.start ?? "", target.record?.at ?? ""].join("|");
}

/**
 * *Check with a photo* on the website (6.7.0): up to [PhotoCheckTarget.pages] pictures, chosen or taken, any saved picture as the
 * owner chose, each shrunk in the browser as *From a photo* on Work shrinks one, and the tick box, *Delete after use*, ticked each
 * time it's shown. The same judge as the phone's and the same earning; what it says is said in its place, through [said].
 */
export function PhotoCheck({ target, disabled, said }: { target: PhotoCheckTarget; disabled: boolean; said: (text: string, bad: boolean) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [chosen, setChosen] = useState<File[] | null>(null);
  const [deleting, setDeleting] = useState(true);
  const [checking, setChecking] = useState(false);

  const picked = (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const all = Array.from(files);
    if (all.length > target.pages) said(`Only ${target.pages} pages go in one check: the first ${target.pages} are taken.`, true);
    setChosen(all.slice(0, target.pages));
    setDeleting(true);
    if (input.current) input.current.value = "";
  };

  const check = () => {
    if (!chosen) return;
    setChecking(true);
    void Promise.all(chosen.map((file) => shrunk(file, target.side).then(base64)))
      .then((pages) => checkPhotos(target, pages, !deleting))
      .then((answer) => said(answer.said, !answer.said.startsWith("Done")))
      .catch((failed: unknown) =>
        said(failed instanceof ApiError && failed.status === 409 ? "A photo is being checked already: try again once it's done." : failed instanceof ApiError && failed.status === 413 ? "Those pictures are too big to check." : "The check didn't go through. Try again.", true),
      )
      .finally(() => {
        setChecking(false);
        setChosen(null);
      });
  };

  return (
    <>
      <label className={disabled || checking ? "action disabled" : "action"}>
        {checking ? "Checking…" : "Check with a photo"}
        <input ref={input} className="hidden" type="file" accept="image/*" multiple disabled={disabled || checking} onChange={(event) => picked(event.target.files)} data-testid="photo-check-input" />
      </label>
      {chosen && !checking ? (
        <div className="photo-check" role="group" aria-label="Pages to check">
          <p>
            {chosen.length} page{chosen.length === 1 ? "" : "s"} chosen. Up to {target.pages} go in one check.
          </p>
          <label className="tick">
            <input type="checkbox" checked={deleting} onChange={(event) => setDeleting(event.target.checked)} /> Delete after use
          </label>
          <button className="button" onClick={check}>
            Check {chosen.length === 1 ? "it" : `the ${chosen.length} pages`}
          </button>
          <button className="quiet" onClick={() => setChosen(null)}>
            Cancel
          </button>
        </div>
      ) : null}
    </>
  );
}
