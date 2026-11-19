# Photos in the chat with Felix (6.8.0, 6.9.0)

A photo sent with a message, from the phone or the website. Felix sees it and answers, makes or changes tasks from it, and can keep it with a task (6.8.0). One sent as proof that work's done is handed to the photo check, which alone decides (6.9.0).

## What the chat is now

- **Text throughout.** `ChatReader` adds each turn as one string; `ChatPrompts.sent` joins the context, the plan and your newest words into the last one. Your message is cut to 2 000 characters (`ChatPrompts.MAX_TEXT`), and the whole to the 40 000 every call is costed for.
- **No words, no message.** `Conversation.send` lets a blank one go, and both boxes' Send is off till there's text.
- **Up to three chat calls a message**, and a web search between: Felix can ask for a task read in full, or a search, and is asked again with it (`ChatRounds`, two rounds more). Each call sends the conversation again, the last twelve messages (`ChatPrompts.HISTORY`).
- **A command is kept in three places**: the phone's outbox till it's answered, the server's record of commands for 90 days, and a row of history for each write of `chat.json`. And it runs twice, on the phone and on the server.
- **Elsewhere a message is its text**: a notification's lines, the website's rows, Felix's story of the day, the record's export.

## 6.8.0: a photo with a message

**Sending.**

- The phone's box gains a photo button beside the microphone: *Camera* or *Gallery*. The website's gains a file button. Four a message at most, shrunk as a check's pages are, shown over the box till sent, with the tick box (*Delete after use*, ticked).
- A photo with no words is a message: `Conversation.send` and both boxes take it, and what reads "has words" as text that isn't blank (the unread count, the newest to answer, what's sent as history) counts a photo as words.

**The bytes go ahead; the command carries a name.** As a task's photo does (`AttachPhoto`):

- The shrunk JPEG is kept as a file by its hash (`Photos.hashOf`). `SendMessage` gains its photos: each a hash and whether it's kept.
- The phone sends each file to a new route, `/chat-photos/{hash}`, before the commands that name it (`Replica.exchange`); the website posts to the same route, then sends its command. The route checks the hash, the size and that it's a JPEG, as the task photos' does, and gives a photo back, so the phone can show one sent from the website and the website one from the phone.
- So nothing but a hash is in the command, the outbox, the server's record, `chat.json` or its history, and nothing of a photo reaches R2 or a backup.
- `ChatMessage` gains its photos (the hash, whether kept, Felix's note once he's seen it): `chat.json` version 13 to 14, the archive's months 12 to 13, nothing in a file from before to change, a fixture with the fields filled.

**What Felix is sent.** A turn with photos is sent as the pictures and then its text, each picture named ("Photo 1:"). Each counts against the chat's 40 000 characters as the characters its tokens would be, so the cap's dearest call stays as it is; one that can't fit is left out and said to be, as words are now.

**His note of it.** His answer gains a note for each photo he was shown: a line of what it is. It's kept on your message, shown in the picture's place once the file's gone, and sent as its line in the history from then on.

**The tick box.**

- *Ticked* (the default): the file is deleted from the server and the phone once the message is answered, with whatever the answer did with it done. Till then, a message that waits, or is tried again, keeps it.
- *Unticked*: the file is kept 14 days from when it's sent, then deleted, its message staying with his note; a chat reset deletes it at once. Not "while its message is in the chat's window", as first put: the window keeps a message while it's among the newest 200 or within 14 days, whichever keeps more (`Outbox.past`, D-96), and never an unread one or one still waiting, so it sets no limit in days (README, *Answered on 19 Nov* 4). While it's kept:
  - it's sent by itself with any message of yours within ten minutes of it;
  - after that the history says it's there, with his note, and Felix asks to see it: one more thing he can ask for, with a task read in full and a search, in the same rounds;
  - the bubble shows the picture.

**What he does with it.**

- Answers about it.
- Makes or changes tasks from it, with the cards and changes he has now: nothing new but his seeing it.
- Keeps it with a task: a new action, a photo of the message kept as that task's photo of the work (AI-23), the task read again with it. The task gets a copy of its own, so the chat's being deleted takes nothing from the task.
- The web search is asked in his words, as now, and is sent no picture.

**Where a message was its text**: a notification's line, his story of the day, what his own messages are told of the chat, the record's export and a reply's quote say "[photo]", with his note where there is one.

**Cost.** About a cent a picture a call: up to three calls for the message it comes with, and again for each later message it's sent with.

**Prompts and goldens.** `chat.txt`, `chat-gmail-off.txt` and `chat.schema.json` change (photos, the note, the action, asking to see one); the schema within `SchemaLimitsTest`'s bounds and compiled by the API once. `FelixInputsDocTest` writes `felix-inputs.md` again with a turn that has a photo.

**Tests.** `ChatPrompts`' (a turn with pictures; the characters a picture counts; ten minutes; the history's line), `Conversation`'s (a photo alone; deleted once answered, kept through a try again; the sweep; asking to see one), the migrations' from every earlier shape, `ApiTest` (the route: a wrong hash, too big, not a JPEG, each client), the website's chat tests.

**Docs.** `spec/felix.md` §The chat, `spec/felix-inputs.md` §The chat, `spec/privacy.md` (what the chat sends, what's kept and how long), `spec/storage-and-backups.md`, `spec/sync.md` and `spec/server.md` (the route), `spec/website.md`, `plan_v4/data-and-migrations.md`.

**At the phone.** A photo with a question, answered. A photo alone. A worksheet made into his task card. A photo kept with a task, on its sheet after. Ticked: the bubble showing his note once he's answered. Unticked: a second question two minutes on answered from the picture; one an hour on, Felix asking to see it. One sent from the website shown on the phone.

## 6.9.0: a photo as proof

Felix isn't the judge. He's a friend, briefed as one, with none of the check's rules; were he to tick work off from a photo there'd be two standards for earning free time, the chat's the softer.

- **A new action**: check these photos against this piece. He gives the piece (its task and step, as his other actions name a step) and, in a line or two, whatever else you said that bears on it.
- **The app runs the photo check** (`Photos.checkJpegs`) with the pictures themselves, the earlier checks' notes, and his line as the student's word: "The student says: …". The check's prompt says a student's word can say what was set, not stand in for the work being seen.
- **The same judge, the same bar** (`PhotoChecker.ACCEPT`), the same earning, the same note kept. The piece is found in the plan as a check's is, or as a slot ticked *Done* that day that can still earn (6.5.0).
- **Its answer comes back in the chat** under his message, as what became of the action, and he's told it in the turns after.
- A call more, under the cap and its rests like any other; one check at a time.
- Four pages, as a message holds. More is the check's own door.
- With the box ticked, the photos are deleted once the check has answered.

**Prompts and goldens.** `chat.txt` and its schema (the action), `photo-check.txt` (the student's word). Judged on the real pages of 6.4.0's set, with a line that claims more than the page shows among them.

**Tests.** The action applied: the check asked with the pictures and his line, its answer kept under his message; a piece gone from the plan said so; a check refused changing nothing.

**Docs.** `spec/felix.md`, `spec/felix-inputs.md`, `spec/blocking-and-protection.md` §Photo checks (the door, and the student's word), `spec/privacy.md`.

**At the phone.** Finished work sent with "done, we were only set the odd ones": checked, passed and earning, its answer in the chat. Half the work sent with "that's all of it": not yet.
