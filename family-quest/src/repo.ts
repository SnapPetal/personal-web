import type { CapResult } from "./cost";
import type { EncounterState } from "./encounter";

export interface CampaignRecord {
  id: string;
  title: string;
  premise: string;
  summary: string;
  status: string;
  created_at: string;
  updated_at: string;
  party: string;
}

export interface CharacterRecord {
  id: string;
  campaign_id: string;
  name: string;
  class_id: string;
  class_name: string;
  ancestry: string;
  level: number;
  xp: number;
  hp: number;
  max_hp: number;
  ac: number;
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  inventory: string;
  portrait: string;
}

export interface MessageRecord {
  id: string;
  campaign_id: string;
  role: string;
  speaker: string;
  content: string;
  created_at: string;
}

export interface EncounterRecord {
  id: string;
  campaign_id: string;
  status: string;
  state_json: string;
  created_at: string;
  updated_at: string;
}

export interface NewCharacter {
  id: string;
  name: string;
  classId: string;
  className: string;
  ancestry: string;
  level: number;
  hp: number;
  maxHp: number;
  ac: number;
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  inventory: string;
  portrait: string;
}

export async function listCampaigns(db: D1Database): Promise<CampaignRecord[]> {
  const result = await db
    .prepare(
      `SELECT c.id, c.title, c.premise, c.summary, c.status, c.created_at, c.updated_at,
              COALESCE(GROUP_CONCAT(ch.name, ', '), '') AS party
       FROM campaigns c
       LEFT JOIN characters ch ON ch.campaign_id = c.id
       GROUP BY c.id
       ORDER BY c.updated_at DESC`,
    )
    .all<CampaignRecord>();
  return result.results;
}

export async function getCampaign(db: D1Database, id: string): Promise<CampaignRecord | null> {
  return db
    .prepare(
      `SELECT id, title, premise, summary, status, created_at, updated_at, '' AS party
       FROM campaigns WHERE id = ?`,
    )
    .bind(id)
    .first<CampaignRecord>();
}

export async function listCharacters(db: D1Database, campaignId: string): Promise<CharacterRecord[]> {
  const result = await db
    .prepare(
      `SELECT id, campaign_id, name, class_id, class_name, ancestry, level, xp, hp, max_hp, ac,
              strength, dexterity, constitution, intelligence, wisdom, charisma, inventory, portrait
       FROM characters WHERE campaign_id = ? ORDER BY created_at ASC`,
    )
    .bind(campaignId)
    .all<CharacterRecord>();
  return result.results;
}

export async function listMessages(db: D1Database, campaignId: string): Promise<MessageRecord[]> {
  const result = await db
    .prepare(
      `SELECT id, campaign_id, role, speaker, content, created_at
       FROM messages WHERE campaign_id = ? ORDER BY created_at DESC LIMIT 80`,
    )
    .bind(campaignId)
    .all<MessageRecord>();
  return result.results.reverse();
}

export async function createQuest(
  db: D1Database,
  campaign: { id: string; title: string; premise: string; summary: string; createdAt: string },
  characters: NewCharacter[],
  opening: { id: string; content: string },
): Promise<void> {
  const statements: D1PreparedStatement[] = [
    db
      .prepare(
        `INSERT INTO campaigns (id, title, premise, summary, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'active', ?, ?)`,
      )
      .bind(campaign.id, campaign.title, campaign.premise, campaign.summary, campaign.createdAt, campaign.createdAt),
    ...characters.map((character) =>
      db
        .prepare(
          `INSERT INTO characters (
             id, campaign_id, name, class_id, class_name, ancestry, level, xp, hp, max_hp, ac,
             strength, dexterity, constitution, intelligence, wisdom, charisma, inventory, portrait, created_at
           ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .bind(
          character.id,
          campaign.id,
          character.name,
          character.classId,
          character.className,
          character.ancestry,
          character.level,
          character.hp,
          character.maxHp,
          character.ac,
          character.strength,
          character.dexterity,
          character.constitution,
          character.intelligence,
          character.wisdom,
          character.charisma,
          character.inventory,
          character.portrait,
          campaign.createdAt,
        ),
    ),
    db
      .prepare(`INSERT INTO messages (id, campaign_id, role, speaker, content, created_at) VALUES (?, ?, 'dm', 'Dungeon Master', ?, ?)`)
      .bind(opening.id, campaign.id, opening.content, campaign.createdAt),
  ];
  await db.batch(statements);
}

export async function touchCampaign(db: D1Database, id: string, updatedAt: string, summary?: string): Promise<void> {
  if (summary === undefined) {
    await db.prepare("UPDATE campaigns SET updated_at = ? WHERE id = ?").bind(updatedAt, id).run();
    return;
  }
  await db.prepare("UPDATE campaigns SET summary = ?, updated_at = ? WHERE id = ?").bind(summary, updatedAt, id).run();
}

export async function addMessage(
  db: D1Database,
  message: { id: string; campaignId: string; role: string; speaker: string; content: string; createdAt: string },
): Promise<void> {
  await db
    .prepare(`INSERT INTO messages (id, campaign_id, role, speaker, content, created_at) VALUES (?, ?, ?, ?, ?, ?)`)
    .bind(message.id, message.campaignId, message.role, message.speaker, message.content, message.createdAt)
    .run();
}

export async function updateCharacter(
  db: D1Database,
  input: { id: string; campaignId: string; hp: number; inventory: string; portrait: string },
): Promise<void> {
  await db
    .prepare("UPDATE characters SET hp = ?, inventory = ?, portrait = ? WHERE id = ? AND campaign_id = ?")
    .bind(input.hp, input.inventory, input.portrait, input.id, input.campaignId)
    .run();
}

export async function levelCharacter(
  db: D1Database,
  input: { id: string; campaignId: string; level: number; hp: number; maxHp: number },
): Promise<void> {
  await db
    .prepare("UPDATE characters SET level = ?, hp = ?, max_hp = ? WHERE id = ? AND campaign_id = ?")
    .bind(input.level, input.hp, input.maxHp, input.id, input.campaignId)
    .run();
}

export async function syncParty(db: D1Database, campaignId: string, state: EncounterState): Promise<void> {
  const party = state.combatants.filter((combatant) => combatant.side === "party");
  if (party.length === 0) return;
  await db.batch(
    party.map((combatant) =>
      db
        .prepare("UPDATE characters SET hp = ? WHERE id = ? AND campaign_id = ?")
        .bind(combatant.hp, combatant.id, campaignId),
    ),
  );
}

export async function awardXp(db: D1Database, campaignId: string, shares: { id: string; xp: number }[]): Promise<void> {
  const grants = shares.filter((share) => share.xp > 0);
  if (grants.length === 0) return;
  await db.batch(
    grants.map((share) =>
      db.prepare("UPDATE characters SET xp = xp + ? WHERE id = ? AND campaign_id = ?").bind(share.xp, share.id, campaignId),
    ),
  );
}

export async function latestEncounter(db: D1Database, campaignId: string): Promise<EncounterRecord | null> {
  return db
    .prepare(
      `SELECT id, campaign_id, status, state_json, created_at, updated_at
       FROM encounters WHERE campaign_id = ? ORDER BY updated_at DESC LIMIT 1`,
    )
    .bind(campaignId)
    .first<EncounterRecord>();
}

export async function insertEncounter(
  db: D1Database,
  row: { id: string; campaignId: string; state: EncounterState; createdAt: string },
): Promise<void> {
  await db
    .prepare(
      `INSERT INTO encounters (id, campaign_id, status, state_json, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
    )
    .bind(row.id, row.campaignId, row.state.status, JSON.stringify(row.state), row.createdAt, row.createdAt)
    .run();
}

export async function saveEncounter(
  db: D1Database,
  row: { id: string; state: EncounterState; updatedAt: string },
): Promise<void> {
  await db
    .prepare("UPDATE encounters SET status = ?, state_json = ?, updated_at = ? WHERE id = ?")
    .bind(row.state.status, JSON.stringify(row.state), row.updatedAt, row.id)
    .run();
}

export async function deleteCampaign(db: D1Database, id: string): Promise<void> {
  await db.batch([
    db.prepare("DELETE FROM messages WHERE campaign_id = ?").bind(id),
    db.prepare("DELETE FROM characters WHERE campaign_id = ?").bind(id),
    db.prepare("DELETE FROM encounters WHERE campaign_id = ?").bind(id),
    db.prepare("DELETE FROM campaigns WHERE id = ?").bind(id),
  ]);
}

/** Same transition as consumeCount: increment only while today's count is under the cap. */
export async function consumeDailyCap(db: D1Database, day: string, cap: number): Promise<CapResult> {
  if (!Number.isInteger(cap) || cap < 0) throw new Error("Cap is invalid.");
  if (cap === 0) {
    const used = await usageCount(db, day);
    return { day, cap, used, remaining: 0, allowed: false };
  }
  const row = await db
    .prepare(
      `INSERT INTO ai_usage (day, kind, "count") VALUES (?1, 'all', 1)
       ON CONFLICT(day, kind) DO UPDATE SET "count" = "count" + 1
       WHERE ai_usage."count" < ?2
       RETURNING "count" AS used`,
    )
    .bind(day, cap)
    .first<{ used: number }>();
  if (!row) {
    const used = await usageCount(db, day);
    return { day, cap, used, remaining: 0, allowed: false };
  }
  return { day, cap, used: row.used, remaining: Math.max(0, cap - row.used), allowed: true };
}

export async function usageCount(db: D1Database, day: string): Promise<number> {
  const row = await db
    .prepare(`SELECT "count" AS used FROM ai_usage WHERE day = ? AND kind = 'all'`)
    .bind(day)
    .first<{ used: number }>();
  return row?.used ?? 0;
}
