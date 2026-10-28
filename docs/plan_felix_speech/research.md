# Felix's speech: the research behind the ideas

What was looked up on 28 October 2026 for [README.md](README.md) and [ideas.md](ideas.md), with what each finding supports and how far it can be trusted. Most of it was read from abstracts, press releases and secondary summaries in one session, not from the papers in full: check a paper before quoting it anywhere else. Nothing here is a trial of a friend-voiced app on its reader; that evidence can only come from Felix in use.

## Model guidance (Anthropic)

| Finding | Source | What it supports |
|---|---|---|
| Examples are "one of the most reliable ways to steer Claude's output format, tone, and structure"; make them relevant and **diverse** "so Claude doesn't pick up unintended patterns"; 3–5 of them, wrapped in `<example>` tags | [Prompting best practices](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices) §Use examples effectively | Examples stay, but as contrastive pairs with a reason, and varied (I-02) |
| "Match your prompt style to the output style you want as closely as possible": the prompt's own style influences the response's | Same page, §Output and formatting, item 3 | The register written in his voice, not in a rule-book's (I-04) |
| "Setting a role in the system prompt focuses Claude's behavior and tone"; put task-specific instructions in the user turn | Same page, §Give Claude a role | A person in a situation, with the moment's instruction in the user turn (I-01, I-05) |
| Positive examples of the wanted concision "tend to be more effective than negative examples or instructions telling the model what not to do" | claude-api skill, `shared/model-migration.md`, Opus 4.7 and Sonnet 5 §Behavioral shifts | The ban lists replaced by shown differences (I-02) |
| Newer models "interpret prompts literally"; holdover style directives "may now over-apply"; re-baseline them | Same file, Sonnet 5 §More literal instruction following | Why forty prohibitions collapse the style to the few moves left (README §Why he still sounds like an app) |
| On Fable 5.1 "prompts and skills written for prior models are often too prescriptive and *reduce* output quality"; "style instructions placed in the first user turn of a session hold better than the same text in the system prompt" | Same file, Fable 5.1 §Behavioral shifts (writing density) | A shorter prompt; the voice reminder in the user turn (I-05) |
| Opus 5.5's effort defaults to `medium`; thinking can't be turned off; `temperature`, `top_p` and `top_k` are removed (a 400 on the 2026-10-21 API version): "use system-prompt instructions for tone/variety instead" | Same file, Opus 5.5 and Sonnet 5 checklists | Variety has to come from the inputs or from sampling several candidates in one answer, never from a sampling knob (I-10) |
| Fable 5.1 at `low` effort "is often competitive with Opus and Sonnet on cost per task while performing better" | Same file, Fable 5.1 §Effort | A re-test of Fable 5.1 at low effort, since run 9 was judged at one setting (I-13) |
| Haiku 5.5: $0.10 / $0.50 per million tokens; Opus 5.5: $4 / $20, cache reads $0.20 | claude-api skill, §Current Models | A second cheap pass is affordable (I-11, I-12) |
| Mid-conversation `system` messages (no beta header) keep the cached prefix; turn-scoped reminders with `clear_at` (beta) | claude-api skill, §Mid-Conversation System Messages | Where a per-message voice reminder could go without a cache reset (I-05) |

## Diversity and copying

| Finding | Source | What it supports |
|---|---|---|
| **Verbalized Sampling**: asking for several candidate answers with their probabilities in one call raised diversity 1.6–2.1× over direct prompting in creative writing and dialogue, quality held, better on more capable models; the paper locates "mode collapse" in preference data's "typicality bias" | Zhang et al. 2025, [arXiv:2510.01171](https://arxiv.org/abs/2510.01171) (abstract and secondary summaries) | Three candidates in one answer, one chosen (I-10) |
| Structured output formats reduce diversity ("The Price of Format") | [arXiv:2505.18949](https://arxiv.org/abs/2505.18949) | Part of why the JSON schema's one `message` field gives the same line three times (m09); a reason to sample within the schema rather than fight it |
| AI prose is more uniform than human prose in word choice and rhythm; models cluster tightly in stylometric space; perplexity (predictability) is the strongest single tell | UCC study (O'Sullivan et al., *Humanities and Social Sciences Communications*, 2025, [press release](https://www.ucc.ie/en/news/2025/new-study-reveals-that-ai-cannot-fully-write-like-a-human.html)); [arXiv:2608.27855](https://arxiv.org/pdf/2608.27855) | The automatic measures for the harness: repeated n-grams across a run, sentence-skeleton repeats, digit density (I-17) |
| Typos in a chatbot's replies *lowered* perceived humanness | Westerman et al. 2018, cited in [arXiv:2510.08912](https://arxiv.org/pdf/2510.08912) | Don't fake typos (I-26) |

## Personalisation from the owner's own words and ratings

| Finding | Source | What it supports |
|---|---|---|
| **TICL** builds a user-specific in-context prompt with no fine-tuning; adding model-generated negative samples *with explanations* gives fine-grained guidance to a user's style, and "explanations are crucial, as it is responsible for up to 77% of the gains relative to a simple ICL baseline" | [arXiv:2502.08972](https://arxiv.org/html/2502.08972v2) (NAACL Findings 2025) | The thumbs-down messages with the owner's written reason are worth more than the thumbs-ups (I-08) |
| **HyPerAlign** infers a user's style and traits from a few examples they wrote, then prompts with those hypotheses rather than a static prefix | [arXiv:2505.00038](https://arxiv.org/html/2505.00038v2) | A style profile of the owner, written by a periodic call from their own chat messages (I-09) |
| Linguistic style matching (function-word convergence) predicts relationship continuation: in instant messages from 86 couples the highest-matching were about 50% more likely to be together three months on; the mechanism is debated (engagement rather than warmth) | Ireland et al. 2011, *Psychological Science* ([PDF](https://sites.socsci.uci.edu/~lpearl/courses/readings/IrelandEtAl2011_RelationshipPrediction.pdf)); Niederhoffer & Pennebaker 2002 | Felix's register tested against the owner's own (I-09); the finding is about people, not bots, so it argues for a test, not a decision |

## Texting conventions

| Finding | Source | What it supports |
|---|---|---|
| A one-word text reply ending in a full stop was rated less sincere than the same reply without one; no such effect for handwritten notes; an exclamation mark raised perceived sincerity. A small study (126 undergraduates); the authors call it modest | Gunraj, Drumm-Hewitt, Dashow, Upadhyay & Klin, "Texting insincerely: the role of the period in text messaging", *Computers in Human Behavior* 2016 ([coverage](https://www.kunc.org/2015-12-20/you-should-watch-the-way-you-punctuate-your-text-messages-period)) | The full stop as a register tool: none when warm, there when cold (I-06) |
| Punctuation in texts is principled, not random; unpunctuated texts are harder to read for tone | Boston College study of 122 college-aged subjects ([Lingua](https://ejournals.bc.edu/index.php/lingua/article/download/9457/8388/16192)) | The same, and why the ends of his sentences carry tone |
| Texts average about 35 characters (US students, 2007); shortenings and dropped punctuation common among British adolescents and young adults | Ling & Baron 2007 ([PDF](https://nl.ijs.si/janes/wp-content/uploads/2014/09/lingbaron07.pdf)); Coventry BPS 2012 poster; Plester & Wood 2009 | One thought a message, more than one message (I-07); the numbers are old and American, so treat them as direction only |
| The owner's own chat messages (aggregate only; the samples stay in `private/`): median 14 words, 11 of 31 end with a full stop, 8 of 31 hold a comma, 3 of 31 all lower case | `private/felix-speech-review/chat_stats.py` on the 19 Oct backup | The gap between the persona's register and the person it's modelled on (I-09) |

## Human-likeness in chatbots

| Finding | Source | What it supports |
|---|---|---|
| Dynamic response delays raised perceived humanness and social presence and satisfaction | Gnewuch et al., ECIS 2018 ([AISeL](https://aisel.aisnet.org/ecis2018_rp/113/)) | A second bubble a moment after the first reads as a person (I-07); the app's notification can't delay, the chat can |
| Human-like typing behaviour (delays, self-corrections) raised perceived naturalness and trust of a ChatGPT-based agent; "no research combines the temporal dimension with the textual dimension" | [arXiv:2510.08912](https://arxiv.org/html/2510.08912v1) | The same; splitting a reply into sentences with pauses is used in [arXiv:2502.18201](https://arxiv.org/pdf/2502.18201) but not tested against unsplit replies |
| A chatbot that remembered past sessions was enjoyed more once rapport had been built through low-risk factual disclosure, but felt intrusive when it opened with emotional familiarity | Cox et al. 2025, [arXiv:2505.20464](https://arxiv.org/abs/2505.20464) | A diary of shared history (I-14, I-15) should recall facts and events, not feelings about them, and never open a day with yesterday's misses |
| Verbatim and paraphrased recall of earlier sessions raised perceived intelligence and engagement, verbatim also privacy concerns (169 participants, three weeks) | [arXiv:2308.04879](https://ar5iv.labs.arxiv.org/html/2308.04879) | Recall in his own words, not quotes (I-14) |
| MemoryBank: layered memory with daily event summaries and time-based forgetting, built for a companion chatbot | Zhong et al., AAAI 2024, [arXiv:2305.10250](https://arxiv.org/abs/2305.10250) | The shape of the diary: a line a day, the old ones folded into a week's line (I-14) |
| Persona consistency drifts and is measurable; persona-assigned models are less consistent under negation; "a helpful assistant"-style personas don't help accuracy | [arXiv:2506.02659](https://arxiv.org/abs/2506.02659) (EMNLP 2025), [arXiv:2311.09718](https://arxiv.org/pdf/2311.09718), [arXiv:2311.10054](https://arxiv.org/abs/2311.10054v2) | The persona is for voice, not for judgement: keep the rule deciding the level and the app deciding the facts (README §What stays) |
| LLM judges "drift toward evaluating on the vibes"; rubric quality matters more than the judge's model; decomposed criteria are more reliable; deterministic checks should keep authority | [arXiv:2411.15594](https://arxiv.org/pdf/2411.15594) (survey), [arXiv:2609.02246](https://arxiv.org/pdf/2609.02246), [arXiv:2601.11783](https://arxiv.org/pdf/2601.11783) | The harness's judge: a short anchored rubric for one question ("the app, or a person?"), beside the counted checks (I-17) |

## Procrastination (what the mood plan rests on, checked again)

| Finding | Source | What it supports |
|---|---|---|
| Students who forgave themselves for procrastinating before one exam procrastinated less before the next (observational, self-report) | Wohl, Pychyl & Bennett 2010 ([coverage](https://siliconcanals.com/t-self-forgiveness-procrastination-two-midterms-carleton/)) | Recovery always answered and nothing held (already built, Q5); the diary never opens a day with a grudge (I-14) |
| A text-based tool asking for small commitments with social accountability improved academic results against plain nudges (nearly 1,000 students, randomised) | AEA Papers and Proceedings 2021 ([IDEAS](https://ideas.repec.org/a/aea/apandp/v111y2021p92-96.html)) | "You said 19:00" is the right lever: a commitment they made, held to by someone who saw it (I-15's "what they said they'd do") |
| Self-compassion mediated shame, guilt and procrastination; guilt itself correlated *negatively* with procrastination in that sample | Iranian correlational study, 2019 | Guilt about the act can help, shame about the self can't: the strong level's limits stay |

## What wasn't found

- No study of what makes a *chatbot's* texts read as a person's to human raters, phrase by phrase. The closest are the stylometry and typing-behaviour studies above.
- No trial of few-shot examples being copied verbatim and what stops it; the fix proposed here (contrastive pairs with reasons, rotated, and three candidates in one answer) is reasoned from the diversity work, not measured.
- No controlled comparison of one bubble against two for perceived humanness.
- No study of "banter" as intimacy in young men's friendships in the UK, only of affiliative humour converging between school friends (Hunter, Fox & Jones 2016, *Journal of Adolescence*).
- No study comparing a persona described in the third person ("Felix is…") with one addressed in the second ("you are…").
