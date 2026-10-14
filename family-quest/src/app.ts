import { narrate, synthesizeSpeech, type AiBinding } from "./ai";
import {
  clearSessionCookie,
  createSession,
  csrfOk,
  passwordsMatch,
  readSession,
  requestIsSecure,
  sessionCookie,
} from "./auth";
import {
  attackBonusFor,
  buildCustomParty,
  buildQuickParty,
  classById,
  damageFor,
  healFor,
  leveledSheet,
  levelUpReady,
  PREMISES,
} from "./classes";
import { capMessage, readCap, utcDay, type CapResult } from "./cost";
import { cryptoRandomSource, describeRoll, rollDice, type RollMode } from "./dice";
import { cannedOpening, enemyMessages, extractJson, parseDmReply, storyMessages } from "./dm";
import {
  applyEnemyChoice,
  applyPlayerAction,
  currentCombatant,
  dismissEncounter,
  parseEnemyChoice,
  partyIds,
  sanitizeEncounterProposal,
  shareXp,
  startEncounter,
  type EncounterState,
  type PartyFighter,
  type PlayerAction,
} from "./encounter";
import {
  addMessage,
  awardXp,
  consumeDailyCap,
  createQuest,
  deleteCampaign,
  getCampaign,
  insertEncounter,
  latestEncounter,
  levelCharacter,
  listCampaigns,
  listCharacters,
  listMessages,
  saveEncounter,
  syncParty,
  touchCampaign,
  updateCharacter,
  usageCount,
  type CampaignRecord,
  type CharacterRecord,
  type MessageRecord,
} from "./repo";
import { clipText } from "./text";
import { homePage, layout, loginPage, newQuestPage, playPage, setupPage } from "./view";

const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.length > 1 ? url.pathname.replace(/\/+$/, "") : "/";
  if (path === "/health" && request.method === "GET") {
    return text("ok");
  }
  if (!secretsReady(env)) return page(setupPage(), 503);

  if (path === "/login" && request.method === "POST") return login(request, env);
  if (path === "/" && request.method === "GET") {
    const session = await readSession(request, env.SESSION_SECRET);
    if (!session) return page(loginPage(null));
    const campaigns = await listCampaigns(env.DB);
    return page(homePage(campaigns, session.csrf));
  }
  if (path === "/logout" && request.method === "POST") return logout(request, env);

  const session = await readSession(request, env.SESSION_SECRET);
  if (!session) return redirect("/");

  if (path === "/quests/new" && request.method === "GET") return page(newQuestPage(session.csrf, null));
  if (path === "/quests" && request.method === "POST") return openQuest(request, env, session.csrf);

  const parts = path.split("/").filter(Boolean);
  if (parts[0] !== "quests" || !parts[1] || !ID.test(parts[1])) return missing(session.csrf);
  const campaignId = parts[1];

  if (parts.length === 2 && request.method === "GET") return renderQuest(request, env, campaignId, session.csrf, {});
  if (request.method !== "POST") return missing(session.csrf);

  if (parts[2] === "act") return act(request, env, campaignId, session.csrf);
  if (parts[2] === "dice") return dice(request, env, campaignId, session.csrf);
  if (parts[2] === "encounter" && !parts[3]) return seekEncounter(request, env, campaignId, session.csrf);
  if (parts[2] === "encounter" && parts[3] === "act") return encounterAct(request, env, campaignId, session.csrf);
  if (parts[2] === "encounter" && parts[3] === "dismiss") return dismiss(request, env, campaignId, session.csrf);
  if (parts[2] === "tts") return speak(request, env, campaignId, session.csrf);
  if (parts[2] === "delete") return removeQuest(request, env, campaignId, session.csrf);
  if (parts[2] === "characters" && parts[3] && ID.test(parts[3]) && !parts[4]) {
    return saveSheet(request, env, campaignId, parts[3], session.csrf);
  }
  if (parts[2] === "characters" && parts[3] && ID.test(parts[3]) && parts[4] === "level") {
    return levelUp(request, env, campaignId, parts[3], session.csrf);
  }
  return missing(session.csrf);
}

async function login(request: Request, env: Env): Promise<Response> {
  const form = await readForm(request);
  const provided = clipText(form?.get("passcode"), 200);
  const matches = await passwordsMatch(provided, env.FAMILY_PASSCODE);
  if (!matches) return page(loginPage("That passcode did not match."), 401);
  const created = await createSession(env.SESSION_SECRET);
  return redirect("/", sessionCookie(created.cookieValue, requestIsSecure(request)));
}

async function logout(request: Request, env: Env): Promise<Response> {
  const session = await readSession(request, env.SESSION_SECRET);
  if (session) {
    const form = await readForm(request);
    const token = form?.get("csrf");
    if (!(await csrfOk(session.csrf, token))) return page(loginPage("The session check failed. Try again."), 403);
  }
  return redirect("/", clearSessionCookie(requestIsSecure(request)));
}

async function openQuest(request: Request, env: Env, csrf: string): Promise<Response> {
  const form = await requireForm(request, csrf);
  if (form instanceof Response) return form;
  const mode = form.get("mode") === "custom" ? "custom" : "quick";
  const party =
    mode === "quick"
      ? buildQuickParty(String(form.get("names") ?? "").split(/\r?\n|,/))
      : buildCustomParty(
          [1, 2, 3, 4].map((slot) => ({
            name: String(form.get(`name_${slot}`) ?? ""),
            classId: String(form.get(`class_${slot}`) ?? ""),
            ancestry: String(form.get(`ancestry_${slot}`) ?? ""),
          })),
        );
  if (!party.ok) return page(newQuestPage(csrf, party.error), 400);

  const premiseId = String(form.get("premise") ?? "picnic");
  const chosen = PREMISES.find((premise) => premise.id === premiseId);
  const customPremise = clipText(form.get("custom_premise"), 600);
  const customTitle = clipText(form.get("title"), 80);
  if (premiseId === "custom" && !customPremise) return page(newQuestPage(csrf, "Write a short premise, or pick one of the ready-made stories."), 400);
  const title = premiseId === "custom" ? customTitle || "Our own quest" : (chosen?.title ?? PREMISES[0].title);
  const premise = premiseId === "custom" ? customPremise : (chosen?.text ?? PREMISES[0].text);

  const createdAt = new Date().toISOString();
  const campaignId = crypto.randomUUID();
  let summary = "";
  let opening = cannedOpening(title, party.party.map((hero) => hero.name));
  const spend = await consumeDailyCap(env.DB, utcDay(new Date()), readCap(env.AI_DAILY_CAP));
  if (spend.allowed) {
    try {
      const result = await narrate(binding(env), models(env), storyMessages({
        premise,
        summary: "",
        party: party.party.map((hero) => sheetLine(hero.name, hero.className, hero.ancestry, hero.hp, hero.maxHp, hero.ac)).join("\n"),
        recent: "",
        speaker: party.party.map((hero) => hero.name).join(", "),
        action: "Begin the quest with a warm opening scene. Do not start combat yet.",
        encounter: "No combat is active.",
      }), 800);
      const reply = parseDmReply(result.text, "");
      opening = reply.narration;
      summary = reply.summary;
      console.log(JSON.stringify({ event: "opening", campaignId, model: result.model }));
    } catch (error) {
      console.error(JSON.stringify({ event: "opening_failed", error: errorText(error) }));
    }
  }

  await createQuest(
    env.DB,
    { id: campaignId, title, premise, summary, createdAt },
    party.party.map((hero) => ({ ...hero, id: crypto.randomUUID() })),
    { id: crypto.randomUUID(), content: opening },
  );
  return redirect(`/quests/${campaignId}`);
}

async function act(request: Request, env: Env, campaignId: string, csrf: string): Promise<Response> {
  const form = await requireForm(request, csrf);
  if (form instanceof Response) return form;
  const loaded = await loadQuest(env, campaignId);
  if (!loaded) return missing(csrf);
  const speaker = clipText(form.get("speaker"), 24);
  const action = clipText(form.get("action"), 500);
  if (!loaded.characters.some((character) => character.name === speaker)) {
    return renderQuest(request, env, campaignId, csrf, { error: "Choose a hero from the party." });
  }
  if (!action) return renderQuest(request, env, campaignId, csrf, { error: "Tell the Dungeon Master what you do." });

  const createdAt = new Date().toISOString();
  await addMessage(env.DB, {
    id: crypto.randomUUID(),
    campaignId,
    role: "player",
    speaker,
    content: action,
    createdAt,
  });
  const encounter = activeEncounter(loaded.encounter);
  const spend = await consumeDailyCap(env.DB, utcDay(new Date()), readCap(env.AI_DAILY_CAP));
  if (!spend.allowed) {
    await addMessage(env.DB, {
      id: crypto.randomUUID(),
      campaignId,
      role: "system",
      speaker: "Family Quest",
      content: capMessage(spend.cap),
      createdAt: new Date().toISOString(),
    });
    await touchCampaign(env.DB, campaignId, createdAt);
    return renderQuest(request, env, campaignId, csrf, {});
  }

  let speakText: string | null = null;
  try {
    const messages = await listMessages(env.DB, campaignId);
    const result = await narrate(
      binding(env),
      models(env),
      storyMessages({
        premise: loaded.campaign.premise,
        summary: loaded.campaign.summary,
        party: loaded.characters.map(characterLine).join("\n"),
        recent: transcript(messages.slice(0, -1)),
        speaker,
        action,
        encounter: encounter ? `Combat is active: ${encounter.state.title}, round ${encounter.state.round}.` : "No combat is active.",
      }),
      800,
    );
    const reply = parseDmReply(result.text, loaded.campaign.summary);
    const stamp = new Date().toISOString();
    await addMessage(env.DB, {
      id: crypto.randomUUID(),
      campaignId,
      role: "dm",
      speaker: "Dungeon Master",
      content: reply.narration,
      createdAt: stamp,
    });
    for (const line of reply.npcLines) {
      await addMessage(env.DB, {
        id: crypto.randomUUID(),
        campaignId,
        role: "npc",
        speaker: line.name,
        content: line.line,
        createdAt: new Date().toISOString(),
      });
    }
    if (reply.rollRequest) {
      const dc = reply.rollRequest.dc === null ? "" : ` DC ${reply.rollRequest.dc}.`;
      await addMessage(env.DB, {
        id: crypto.randomUUID(),
        campaignId,
        role: "system",
        speaker: "Roll",
        content: `${reply.rollRequest.who}, roll ${reply.rollRequest.dice} ${reply.rollRequest.reason}.${dc}`,
        createdAt: new Date().toISOString(),
      });
    }
    await touchCampaign(env.DB, campaignId, stamp, reply.summary);
    const proposal = encounter ? null : sanitizeEncounterProposal(reply.encounter);
    if (proposal) await persistNewEncounter(env, campaignId, loaded.characters, proposal);
    speakText = [reply.narration, ...reply.npcLines.map((line) => `${line.name}: ${line.line}`)].join(" ");
    console.log(JSON.stringify({ event: "dm_reply", campaignId, model: result.model }));
  } catch (error) {
    console.error(JSON.stringify({ event: "dm_failed", campaignId, error: errorText(error) }));
    await addMessage(env.DB, {
      id: crypto.randomUUID(),
      campaignId,
      role: "system",
      speaker: "Family Quest",
      content: "The Dungeon Master lost the thread for a moment. Your action is saved. Try sending it once more.",
      createdAt: new Date().toISOString(),
    });
  }
  return renderQuest(request, env, campaignId, csrf, { speakText });
}

async function dice(request: Request, env: Env, campaignId: string, csrf: string): Promise<Response> {
  const form = await requireForm(request, csrf);
  if (form instanceof Response) return form;
  const loaded = await loadQuest(env, campaignId);
  if (!loaded) return missing(csrf);
  const speaker = clipText(form.get("speaker"), 24) || "Party";
  const mode = rollMode(form.get("mode"));
  try {
    const roll = rollDice(
      {
        count: Number(form.get("count")),
        sides: Number(form.get("sides")),
        modifier: Number(form.get("modifier")),
        mode,
      },
      cryptoRandomSource(),
    );
    await addMessage(env.DB, {
      id: crypto.randomUUID(),
      campaignId,
      role: "roll",
      speaker,
      content: describeRoll(roll),
      createdAt: new Date().toISOString(),
    });
    await touchCampaign(env.DB, campaignId, new Date().toISOString());
    return renderQuest(request, env, campaignId, csrf, {});
  } catch (error) {
    return renderQuest(request, env, campaignId, csrf, { error: errorText(error) });
  }
}

async function seekEncounter(request: Request, env: Env, campaignId: string, csrf: string): Promise<Response> {
  const form = await requireForm(request, csrf);
  if (form instanceof Response) return form;
  const loaded = await loadQuest(env, campaignId);
  if (!loaded) return missing(csrf);
  if (activeEncounter(loaded.encounter)) {
    return renderQuest(request, env, campaignId, csrf, { error: "Finish or tuck away the current challenge first." });
  }
  const spend = await consumeDailyCap(env.DB, utcDay(new Date()), readCap(env.AI_DAILY_CAP));
  if (!spend.allowed) return renderQuest(request, env, campaignId, csrf, { error: capMessage(spend.cap) });
  try {
    const result = await narrate(
      binding(env),
      models(env),
      storyMessages({
        premise: loaded.campaign.premise,
        summary: loaded.campaign.summary,
        party: loaded.characters.map(characterLine).join("\n"),
        recent: transcript(loaded.messages),
        speaker: "Party",
        action: "The party looks for a small, kind challenge that fits the story. Include an encounter object now.",
        encounter: "No combat is active.",
      }),
      800,
    );
    const reply = parseDmReply(result.text, loaded.campaign.summary);
    await addMessage(env.DB, {
      id: crypto.randomUUID(),
      campaignId,
      role: "dm",
      speaker: "Dungeon Master",
      content: reply.narration,
      createdAt: new Date().toISOString(),
    });
    await touchCampaign(env.DB, campaignId, new Date().toISOString(), reply.summary);
    const proposal = sanitizeEncounterProposal(reply.encounter);
    if (proposal) await persistNewEncounter(env, campaignId, loaded.characters, proposal);
    else {
      await addMessage(env.DB, {
        id: crypto.randomUUID(),
        campaignId,
        role: "system",
        speaker: "Family Quest",
        content: "The path stays peaceful for now. Try again when the story is ready for a challenge.",
        createdAt: new Date().toISOString(),
      });
    }
    return renderQuest(request, env, campaignId, csrf, { speakText: reply.narration });
  } catch (error) {
    console.error(JSON.stringify({ event: "encounter_failed", campaignId, error: errorText(error) }));
    return renderQuest(request, env, campaignId, csrf, {
      error: "The Dungeon Master could not set a challenge just now. Dice and the story still work.",
    });
  }
}

async function encounterAct(request: Request, env: Env, campaignId: string, csrf: string): Promise<Response> {
  const form = await requireForm(request, csrf);
  if (form instanceof Response) return form;
  const loaded = await loadQuest(env, campaignId);
  if (!loaded?.encounter || !activeEncounter(loaded.encounter)) return renderQuest(request, env, campaignId, csrf, { error: "There is no active challenge." });
  const encounter = loaded.encounter;
  if (form.get("action") === "enemy") {
    const resolved = await resolveEnemies(env, loaded.campaign, encounter.state, 3);
    await finishEncounter(env, campaignId, encounter.id, encounter.state.status, resolved.state);
    return renderQuest(request, env, campaignId, csrf, {
      error: resolved.stoppedForCap ? capMessage(readCap(env.AI_DAILY_CAP)) : null,
    });
  }

  const action = playerAction(form);
  if (!action) return renderQuest(request, env, campaignId, csrf, { error: "Choose an action from the list." });
  const result = applyPlayerAction(encounter.state, action, cryptoRandomSource());
  if (result.error) return renderQuest(request, env, campaignId, csrf, { error: result.error });
  let state = result.state;
  let stopped = false;
  if (state.status === "active") {
    const resolved = await resolveEnemies(env, loaded.campaign, state, 3);
    state = resolved.state;
    stopped = resolved.stoppedForCap;
  }
  await finishEncounter(env, campaignId, encounter.id, encounter.state.status, state);
  return renderQuest(request, env, campaignId, csrf, { error: stopped ? capMessage(readCap(env.AI_DAILY_CAP)) : null });
}

async function dismiss(request: Request, env: Env, campaignId: string, csrf: string): Promise<Response> {
  const form = await requireForm(request, csrf);
  if (form instanceof Response) return form;
  const loaded = await loadQuest(env, campaignId);
  if (!loaded?.encounter) return redirect(`/quests/${campaignId}`);
  await saveEncounter(env.DB, {
    id: loaded.encounter.id,
    state: dismissEncounter(loaded.encounter.state),
    updatedAt: new Date().toISOString(),
  });
  return redirect(`/quests/${campaignId}`);
}

async function speak(request: Request, env: Env, campaignId: string, csrf: string): Promise<Response> {
  const length = request.headers.get("content-length");
  if (length && Number(length) > 20_000) return voiceFallback();
  let payload: unknown = null;
  try {
    payload = await request.json();
  } catch {
    payload = null;
  }
  const token = request.headers.get("x-family-quest-token");
  if (!(await csrfOk(csrf, token))) return voiceFallback();
  const campaign = await getCampaign(env.DB, campaignId);
  if (!campaign) return voiceFallback();
  const record = payload && typeof payload === "object" ? (payload as { text?: unknown }).text : "";
  const text = clipText(record, 1200);
  if (!text) return voiceFallback();
  const spend = await consumeDailyCap(env.DB, utcDay(new Date()), readCap(env.AI_DAILY_CAP));
  if (!spend.allowed) return voiceFallback();
  try {
    const audio = await synthesizeSpeech(binding(env), env.TTS_MODEL, text);
    if (!audio) return voiceFallback();
    const body = new ArrayBuffer(audio.bytes.byteLength);
    new Uint8Array(body).set(audio.bytes);
    return new Response(body, {
      headers: {
        "content-type": audio.contentType,
        "cache-control": "no-store",
        "x-content-type-options": "nosniff",
      },
    });
  } catch (error) {
    console.error(JSON.stringify({ event: "tts_failed", campaignId, error: errorText(error) }));
    return voiceFallback();
  }
}

async function removeQuest(request: Request, env: Env, campaignId: string, csrf: string): Promise<Response> {
  const form = await requireForm(request, csrf);
  if (form instanceof Response) return form;
  if (form.get("confirm") !== "yes") {
    return renderQuest(request, env, campaignId, csrf, { error: "Check the box to delete this quest." });
  }
  await deleteCampaign(env.DB, campaignId);
  return redirect("/");
}

async function saveSheet(request: Request, env: Env, campaignId: string, characterId: string, csrf: string): Promise<Response> {
  const form = await requireForm(request, csrf);
  if (form instanceof Response) return form;
  const characters = await listCharacters(env.DB, campaignId);
  const character = characters.find((entry) => entry.id === characterId);
  if (!character) return missing(csrf);
  const hp = Number(form.get("hp"));
  if (!Number.isInteger(hp) || hp < 0 || hp > character.max_hp) {
    return renderQuest(request, env, campaignId, csrf, { error: "Hit points have to stay between 0 and the hero's maximum." });
  }
  await updateCharacter(env.DB, {
    id: characterId,
    campaignId,
    hp,
    inventory: clipText(form.get("inventory"), 400),
    portrait: clipText(form.get("portrait"), 300),
  });
  return redirect(`/quests/${campaignId}`);
}

async function levelUp(request: Request, env: Env, campaignId: string, characterId: string, csrf: string): Promise<Response> {
  const form = await requireForm(request, csrf);
  if (form instanceof Response) return form;
  const characters = await listCharacters(env.DB, campaignId);
  const character = characters.find((entry) => entry.id === characterId);
  if (!character || !levelUpReady(character.level, character.xp)) return redirect(`/quests/${campaignId}`);
  const heroClass = classById(character.class_id);
  const next = leveledSheet(character.level, character.hp, character.max_hp, heroClass.hitDie);
  await levelCharacter(env.DB, { id: characterId, campaignId, ...next });
  return redirect(`/quests/${campaignId}`);
}

async function resolveEnemies(
  env: Env,
  campaign: CampaignRecord,
  start: EncounterState,
  limit: number,
): Promise<{ state: EncounterState; stoppedForCap: boolean }> {
  let state = start;
  for (let step = 0; step < limit; step += 1) {
    const actor = currentCombatant(state);
    if (!actor || actor.side !== "enemy" || state.status !== "active") break;
    const spend = await consumeDailyCap(env.DB, utcDay(new Date()), readCap(env.AI_DAILY_CAP));
    if (!spend.allowed) return { state, stoppedForCap: true };
    let choice = parseEnemyChoice(null);
    try {
      const result = await narrate(
        binding(env),
        models(env),
        enemyMessages({
          summary: campaign.summary,
          battle: battleBrief(state),
          actor: `${actor.name} (${actor.id})`,
        }),
        250,
      );
      choice = parseEnemyChoice(extractJson(result.text));
      if (!choice.flavor) choice = { ...choice, flavor: `${actor.name} moves.` };
    } catch (error) {
      console.error(JSON.stringify({ event: "enemy_ai_failed", error: errorText(error) }));
      choice = { action: "attack", targetId: "", flavor: `${actor.name} shuffles forward.` };
    }
    state = applyEnemyChoice(state, choice, cryptoRandomSource()).state;
  }
  return { state, stoppedForCap: false };
}

async function finishEncounter(
  env: Env,
  campaignId: string,
  encounterId: string,
  previousStatus: EncounterState["status"],
  state: EncounterState,
): Promise<void> {
  if (previousStatus !== "victory" && state.status === "victory") {
    const ids = partyIds(state);
    const shares = shareXp(ids.length, state.xpAwarded);
    await awardXp(
      env.DB,
      campaignId,
      ids.map((id, index) => ({ id, xp: shares[index] ?? 0 })),
    );
  }
  await syncParty(env.DB, campaignId, state);
  await saveEncounter(env.DB, { id: encounterId, state, updatedAt: new Date().toISOString() });
  const note = state.log.at(-1)?.text;
  if (note && state.status !== "active") {
    await addMessage(env.DB, {
      id: crypto.randomUUID(),
      campaignId,
      role: "system",
      speaker: "Challenge",
      content: note,
      createdAt: new Date().toISOString(),
    });
  }
}

async function persistNewEncounter(
  env: Env,
  campaignId: string,
  characters: CharacterRecord[],
  proposal: NonNullable<ReturnType<typeof sanitizeEncounterProposal>>,
): Promise<void> {
  const state = startEncounter({
    title: proposal.title,
    party: characters.map(fighter),
    enemies: proposal.enemies,
    loot: proposal.loot,
    xp: proposal.xp,
    random: cryptoRandomSource(),
  });
  await insertEncounter(env.DB, { id: crypto.randomUUID(), campaignId, state, createdAt: new Date().toISOString() });
  await syncParty(env.DB, campaignId, state);
}

async function renderQuest(
  request: Request,
  env: Env,
  campaignId: string,
  csrf: string,
  options: { error?: string | null; speakText?: string | null },
): Promise<Response> {
  const loaded = await loadQuest(env, campaignId);
  if (!loaded) return missing(csrf);
  const cap = await currentCap(env);
  const html = playPage({
    campaign: loaded.campaign,
    characters: loaded.characters,
    messages: loaded.messages,
    encounter: loaded.encounter,
    csrf,
    cap,
    error: options.error ?? null,
    speakText: options.speakText ?? null,
    fragment: request.headers.get("HX-Request") === "true",
  });
  return page(html);
}

async function loadQuest(env: Env, campaignId: string): Promise<{
  campaign: CampaignRecord;
  characters: CharacterRecord[];
  messages: MessageRecord[];
  encounter: { id: string; state: EncounterState } | null;
} | null> {
  const campaign = await getCampaign(env.DB, campaignId);
  if (!campaign) return null;
  const [characters, messages, encounterRow] = await Promise.all([
    listCharacters(env.DB, campaignId),
    listMessages(env.DB, campaignId),
    latestEncounter(env.DB, campaignId),
  ]);
  return { campaign, characters, messages, encounter: readEncounter(encounterRow?.id ?? null, encounterRow?.state_json ?? null) };
}

async function currentCap(env: Env): Promise<CapResult> {
  const cap = readCap(env.AI_DAILY_CAP);
  const day = utcDay(new Date());
  const used = await usageCount(env.DB, day);
  return { day, cap, used, remaining: Math.max(0, cap - used), allowed: used < cap };
}

function fighter(character: CharacterRecord): PartyFighter {
  const heroClass = classById(character.class_id);
  const primary = character[heroClass.primary];
  return {
    id: character.id,
    name: character.name,
    hp: character.hp,
    maxHp: character.max_hp,
    ac: character.ac,
    dexterity: character.dexterity,
    attackBonus: attackBonusFor(character.level, primary),
    damage: damageFor(heroClass.damage, primary),
    heal: healFor(heroClass.heal, character.wisdom),
  };
}

function playerAction(form: FormData): PlayerAction | null {
  const actorId = String(form.get("actor") ?? "");
  const action = String(form.get("action") ?? "");
  const enemy = String(form.get("target_enemy") ?? "");
  const ally = String(form.get("target_ally") ?? "");
  if (action === "attack") return { type: "attack", actorId, targetId: enemy };
  if (action === "dodge") return { type: "dodge", actorId };
  if (action === "help") return { type: "help", actorId, targetId: ally };
  if (action === "heal") return { type: "heal", actorId, targetId: ally };
  if (action === "flee") return { type: "flee", actorId };
  return null;
}

function activeEncounter(
  encounter: { id: string; state: EncounterState } | null,
): { id: string; state: EncounterState } | null {
  if (!encounter || encounter.state.status !== "active" || encounter.state.dismissed) return null;
  return encounter;
}

function readEncounter(id: string | null, json: string | null): { id: string; state: EncounterState } | null {
  if (!id || !json) return null;
  try {
    const state = JSON.parse(json) as EncounterState;
    if (!state || !Array.isArray(state.combatants) || !Array.isArray(state.order)) return null;
    return { id, state };
  } catch {
    return null;
  }
}

function battleBrief(state: EncounterState): string {
  const lines = state.combatants.map(
    (combatant) =>
      `${combatant.id} | ${combatant.name} | ${combatant.side} | HP ${combatant.hp}/${combatant.maxHp} | AC ${combatant.ac} | ${combatant.out} | ${combatant.conditions.join(", ") || "no conditions"}`,
  );
  const actor = currentCombatant(state);
  return `${lines.join("\n")}\nRound ${state.round}. Now acting: ${actor?.name ?? "nobody"} (${actor?.id ?? ""}).`;
}

function transcript(messages: MessageRecord[]): string {
  return messages
    .slice(-8)
    .map((message) => `${message.speaker || message.role}: ${message.content.slice(0, 400)}`)
    .join("\n");
}

function characterLine(character: CharacterRecord): string {
  return sheetLine(
    character.name,
    character.class_name,
    character.ancestry,
    character.hp,
    character.max_hp,
    character.ac,
  ) + ` STR ${character.strength} DEX ${character.dexterity} CON ${character.constitution} INT ${character.intelligence} WIS ${character.wisdom} CHA ${character.charisma}. Inventory: ${character.inventory}`;
}

function sheetLine(name: string, className: string, ancestry: string, hp: number, maxHp: number, ac: number): string {
  return `${name}: ${ancestry} ${className}, HP ${hp}/${maxHp}, AC ${ac}.`;
}

function rollMode(value: FormDataEntryValue | null): RollMode {
  if (value === "advantage" || value === "disadvantage") return value;
  return "normal";
}

function models(env: Env): { primary: string; fallback: string } {
  return { primary: env.AI_MODEL, fallback: env.AI_MODEL_FALLBACK };
}

function binding(env: Env): AiBinding {
  return {
    run(model: string, input: Record<string, unknown>) {
      return env.AI.run(model, input);
    },
  };
}

async function requireForm(request: Request, csrf: string): Promise<FormData | Response> {
  const form = await readForm(request);
  if (!form) return page(layout({ title: "Try again", csrf, body: "<p>That form could not be read.</p>" }), 400);
  if (!(await csrfOk(csrf, form.get("csrf")))) {
    return page(layout({ title: "Try again", csrf, body: "<p>The session check failed. Reload the page and try again.</p>" }), 403);
  }
  return form;
}

async function readForm(request: Request): Promise<FormData | null> {
  const length = request.headers.get("content-length");
  if (length && Number(length) > 100_000) return null;
  try {
    return await request.formData();
  } catch {
    return null;
  }
}

function secretsReady(env: Env): boolean {
  return env.FAMILY_PASSCODE.trim().length >= 4 && env.SESSION_SECRET.trim().length >= 16;
}

function voiceFallback(): Response {
  return Response.json({ fallback: "browser" }, { headers: { "cache-control": "no-store" } });
}

function missing(csrf: string): Response {
  return page(layout({ title: "Missing", csrf, body: `<h1 class="h3">That page wandered off.</h1><p><a href="/">Back to quests</a></p>` }), 404);
}

function redirect(location: string, cookie?: string): Response {
  const headers = new Headers({ location, "cache-control": "no-store" });
  if (cookie) headers.set("set-cookie", cookie);
  return new Response(null, { status: 303, headers });
}

function text(body: string): Response {
  return new Response(body, { headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } });
}

function page(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
      "content-security-policy":
        "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self'; font-src 'self'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
    },
  });
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : "Something went wrong.";
}
