///
/// Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance
/// with the License.  You may obtain a copy of the License at
///
///  http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing,
/// software distributed under the License is distributed on an
/// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
/// KIND, either express or implied.  See the License for the
/// specific language governing permissions and limitations
/// under the License.
///

export interface ChunkData {
  id: string;
  text: string;
  url: string;
  title: string;
}

export interface ScoredChunk {
  chunk: ChunkData;
  score: number;
}

// Words too common to carry retrieval signal (incl. doc-meta phrasing like "project").
const STOPWORDS = new Set([
  'what', 'are', 'is', 'a', 'an', 'the', 'of', 'to', 'how', 'do', 'does', 'did', 'can',
  'will', 'i', 'this', 'that', 'about', 'and', 'or', 'for', 'any', 'it', 'its', 'in', 'on',
  'with', 'your', 'you', 'me', 'my', 'we', 'project', 'projects', 'there', 'some',
]);

// Soft synonyms for "what a project offers". The embedding model handles paraphrases
// poorly on short list-like content, so we expand these structural terms to each other:
// asking for "extensions" should also match a "Features"/"Modules" section and vice versa.
const SYNONYMS: Record<string, string[]> = {
  feature: ['module', 'extension', 'capability'],
  module: ['feature', 'extension', 'component'],
  extension: ['module', 'feature', 'plugin'],
  capability: ['feature', 'module'],
  component: ['module', 'feature'],
  plugin: ['extension', 'module'],
};

// Weight of the lexical (keyword) score relative to cosine. Cosine for on-topic chunks of
// all-MiniLM-L6-v2 sits around 0.2-0.5; a strong keyword/heading match adds up to this.
const LEX_WEIGHT = 0.5;
// Synonym matches count, but less than the user's literal words — otherwise expanding
// "extensions" to "module" would let unrelated "module" chunks outrank the real answer.
const SYNONYM_WEIGHT = 0.3;

interface QueryTerms {
  primary: string[];   // the user's own content words
  synonyms: string[];  // expansions (see SYNONYMS), excluding any already in primary
}

function queryTerms(query: string): QueryTerms {
  const primary = [...new Set(
    query.toLowerCase().split(/\W+/).filter(w => w.length > 2 && !STOPWORDS.has(w)),
  )];
  const synonyms = new Set<string>();
  for (const w of primary) {
    const singular = w.replace(/s$/, '');
    (SYNONYMS[w] ?? SYNONYMS[singular] ?? []).forEach(s => {
      if (!primary.includes(s)) synonyms.add(s);
    });
  }
  return { primary, synonyms: [...synonyms] };
}

// Fraction of terms found in the text, with naive singular stemming.
function fraction(terms: string[], text: string): number {
  if (terms.length === 0) return 0;
  let hits = 0;
  for (const term of terms) {
    const stem = term.replace(/s$/, '');
    if (text.includes(term) || text.includes(stem)) hits++;
  }
  return hits / terms.length;
}

// Lexical relevance: literal-term coverage plus a discounted synonym coverage.
function lexicalScore(terms: QueryTerms, haystack: string): number {
  const text = haystack.toLowerCase();
  return fraction(terms.primary, text) + SYNONYM_WEIGHT * fraction(terms.synonyms, text);
}

function cosineSimilarity(a: number[], b: number[]): number {
  let dot = 0;
  let normA = 0;
  let normB = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
    normA += a[i] * a[i];
    normB += b[i] * b[i];
  }
  const denom = Math.sqrt(normA) * Math.sqrt(normB);
  return denom === 0 ? 0 : dot / denom;
}

// Minimum similarity for a chunk to be considered relevant. all-MiniLM-L6-v2 produces
// fairly modest cosines: on-topic documentation here lands around 0.3-0.5 and clearly
// unrelated content is near or below 0. 0.15 keeps genuine matches (which a 0.3 floor
// wrongly discarded) while still dropping noise. We always keep at least the top result
// so the model gets some context rather than an empty prompt.
const MIN_SCORE = 0.15;

export function retrieveRelevantChunks(
    question: string,
    queryEmbedding: number[],
    chunks: ChunkData[],
    storedEmbeddings: Map<string, number[]>,
    topK = 15,
): ScoredChunk[] {
  // Hybrid retrieval: blend semantic similarity (cosine) with a lexical/keyword score over
  // ALL chunks. Pure cosine with this model is too weak for abstract/structural questions
  // ("what are the extensions/modules/features") — the answer text exists but doesn't win
  // on cosine alone. The lexical term (with synonym expansion) surfaces the right section.
  const terms = queryTerms(question);
  const scored: ScoredChunk[] = [];

  for (const chunk of chunks) {
    const emb = storedEmbeddings.get(chunk.id);
    if (!emb) continue;

    const cos = cosineSimilarity(queryEmbedding, emb);
    const lex = lexicalScore(terms, `${chunk.title}\n${chunk.text}`);
    scored.push({ chunk, score: cos + LEX_WEIGHT * lex });
  }

  scored.sort((a, b) => b.score - a.score);

  // Drop weakly-related chunks, but never return nothing.
  const filtered = scored.filter(c => c.score >= MIN_SCORE);
  const kept = filtered.length > 0 ? filtered : scored.slice(0, 1);

  return kept.slice(0, topK);
}

// Overall budget for the retrieved context, in characters. Keeps the prompt small enough
// for the in-browser chat model while leaving room for the (un-truncated) question.
const MAX_CONTEXT_CHARS = 1500;
// Per-chunk cap so a single long chunk can't consume the whole budget.
const MAX_CHUNK_CHARS = 700;

export function buildPrompt(question: string, relevant: ScoredChunk[]): string {
  const parts: string[] = [];
  let used = 0;
  for (const r of relevant) {
    const text = r.chunk.text.length > MAX_CHUNK_CHARS
      ? r.chunk.text.slice(0, MAX_CHUNK_CHARS)
      : r.chunk.text;
    const block = `From [${r.chunk.title}](${r.chunk.url}):\n${text}`;
    if (used + block.length > MAX_CONTEXT_CHARS && parts.length > 0) break;
    parts.push(block);
    used += block.length;
  }
  const context = parts.join('\n\n');

  return `
You are a documentation assistant. Answer the user's question using ONLY the context below.
- If the answer is in the context, answer concisely and cite the relevant source link(s) using their markdown links.
- If the context does not contain the answer, say you don't know based on the documentation; do not invent information.

CONTEXT:
${context}

QUESTION:
${question}

ANSWER:
`.trim();
}

// Minimal, dependency-free Markdown -> HTML renderer for assistant replies. The chat UI
// injects this via innerHTML, so we escape first and only emit a small, known tag set
// (lists, bold, italic, code, links, headings). The local model tends to answer in
// Markdown; without this its '*' bullets and '**bold**' leak through as raw text.
export function renderMarkdown(text: string): string {
  const esc = (s: string): string =>
    s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  const inline = (s: string): string => {
    let out = esc(s);
    out = out.replace(/`([^`]+)`/g, '<code>$1</code>');
    out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    out = out.replace(/__([^_]+)__/g, '<strong>$1</strong>');
    out = out.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    return out;
  };

  const out: string[] = [];
  let inList = false;
  const closeList = (): void => {
    if (inList) { out.push('</ul>'); inList = false; }
  };

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trimEnd();
    const bullet = line.match(/^\s*[-*+]\s+(.*)$/);
    if (bullet) {
      if (!inList) { out.push('<ul>'); inList = true; }
      out.push(`<li>${inline(bullet[1])}</li>`);
      continue;
    }
    closeList();
    if (line.trim() === '') continue;
    const heading = line.match(/^(#{1,4})\s+(.*)$/);
    if (heading) {
      const level = heading[1].length;
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`);
      continue;
    }
    out.push(`<p>${inline(line)}</p>`);
  }
  closeList();
  return out.join('\n');
}
