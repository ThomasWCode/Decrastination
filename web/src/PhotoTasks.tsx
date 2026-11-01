import { useRef, useState } from "react";
import { ApiError, readPhoto } from "./api";
import { NewTask } from "./NewTask";
import type { OwnTaskFormView, PhotoRead } from "./types";

/** The JPEG the model's sent: upright, as a canvas draws a photo the way its EXIF says, and no longer than [side]. */
async function shrunk(file: File, side: number): Promise<Blob> {
  const photo = await createImageBitmap(file, { imageOrientation: "from-image" });
  const scale = Math.min(1, side / Math.max(photo.width, photo.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(photo.width * scale);
  canvas.height = Math.round(photo.height * scale);
  const context = canvas.getContext("2d");
  if (!context) throw new Error("No canvas");
  context.drawImage(photo, 0, 0, canvas.width, canvas.height);
  photo.close();
  return new Promise((done, failed) => canvas.toBlob((blob) => (blob ? done(blob) : failed(new Error("No JPEG"))), "image/jpeg", 0.85));
}

/** A photo of homework read into tasks (NF-01), each then checked in the form, one at a time, and only kept if it's added there. */
export function PhotoTasks({ form, side }: { form: OwnTaskFormView; side: number }) {
  const [reading, setReading] = useState(false);
  const [read, setRead] = useState<PhotoRead | null>(null);
  const [at, setAt] = useState(0);
  const [said, setSaid] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const chosen = (file: File) => {
    setSaid(null);
    setRead(null);
    setAt(0);
    setReading(true);
    shrunk(file, side)
      .then(readPhoto)
      .then((answer) => {
        setRead(answer);
        setSaid(answer.why ?? null);
      })
      .catch((failed: unknown) => setSaid(failed instanceof ApiError && failed.status === 409 ? "Another photo is being read. Try again once it's done." : "The photo couldn't be read. Try again, or add it with New task."))
      .finally(() => {
        setReading(false);
        if (input.current) input.current.value = "";
      });
  };

  const task = read?.tasks[at];
  const over = () => (read && at + 1 < read.tasks.length ? setAt(at + 1) : setRead(null));
  return (
    <>
      <label className="button">
        {reading ? "Reading the photo…" : "From a photo"}
        <input ref={input} className="hidden" type="file" accept="image/*" disabled={reading} onChange={(event) => event.target.files?.[0] && chosen(event.target.files[0])} />
      </label>
      {said ? (
        <p className="problem" role="status">
          {said}
        </p>
      ) : null}
      {read && task ? (
        <div className="photo-tasks">
          <p className="note">
            Read from your photo: {at + 1} of {read.tasks.length}. Check it, then add it.
          </p>
          {read.question ? <p className="note">{read.question}</p> : null}
          {read.leftOut.length > 0 ? <p className="note">Left out: {read.leftOut.join("; ")}</p> : null}
          <NewTask key={task.sourceId} form={form} from={task} close={over} />
        </div>
      ) : null}
    </>
  );
}
