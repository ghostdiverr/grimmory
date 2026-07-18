const FORMAT_TAGS = ['EPUB', 'MOBI', 'AZW3', 'PDF', 'FB2', 'CBZ', 'CBR', 'M4B', 'MP3'] as const;
const SOURCE_TAGS = ['RETAIL', 'SCAN', 'WEB', 'WEBRIP'] as const;
const BITRATE_PATTERN = /\b(\d{2,3})\s?K(?:BPS|B\/S)\b/;

/**
 * Cosmetic, display-only extraction of a short "what does this release look
 * like" label from its raw title — NOT a scoring/matching engine. v1 scope
 * is explicitly "no scoring/ranking beyond the default seeders sort", so this
 * only recognizes a handful of common format/source/bitrate tags found in
 * real-world indexer release titles and joins whatever it finds with " · ".
 *
 * Returns an empty string when nothing recognizable is found — callers are
 * expected to fall back to a placeholder (e.g. "-") in the UI.
 */
export function parseReleaseQuality(title: string | null | undefined): string {
  if (!title) {
    return '';
  }

  const upper = title.toUpperCase();

  const format = FORMAT_TAGS.find(tag => new RegExp(`\\b${tag}\\b`).test(upper));
  const source = SOURCE_TAGS.find(tag => new RegExp(`\\b${tag}\\b`).test(upper));
  const bitrateMatch = upper.match(BITRATE_PATTERN);

  const parts = [format, source, bitrateMatch ? `${bitrateMatch[1]}kbps` : undefined].filter(
    (part): part is string => !!part
  );

  return parts.join(' · ');
}
