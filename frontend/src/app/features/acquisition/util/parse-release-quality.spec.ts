import {describe, expect, it} from 'vitest';
import {parseReleaseQuality} from './parse-release-quality';

describe('parseReleaseQuality', () => {
  it('extracts format, source, and bitrate from a real-world-looking audiobook title', () => {
    expect(parseReleaseQuality('The Hobbit - J.R.R. Tolkien (Unabridged) [M4B RETAIL 64kbps]'))
      .toBe('M4B · RETAIL · 64kbps');
  });

  it('extracts a single format tag from an ebook title', () => {
    expect(parseReleaseQuality('Project Hail Mary - Andy Weir [EPUB]')).toBe('EPUB');
  });

  it('extracts source and bitrate without a format tag', () => {
    expect(parseReleaseQuality('Some Audiobook WEBRIP 128 KBPS')).toBe('WEBRIP · 128kbps');
  });

  it('extracts MP3 format alongside a WEB source tag', () => {
    expect(parseReleaseQuality('Dune - Frank Herbert (MP3, WEB)')).toBe('MP3 · WEB');
  });

  it('recognizes a comic book archive format', () => {
    expect(parseReleaseQuality('Batman Vol 1 CBZ SCAN')).toBe('CBZ · SCAN');
  });

  it('recognizes bitrate expressed as "kb/s"', () => {
    expect(parseReleaseQuality('Random Audiobook 96KB/S')).toBe('96kbps');
  });

  it('returns an empty string when nothing recognizable is found', () => {
    expect(parseReleaseQuality('A Totally Generic Release Title')).toBe('');
  });

  it('returns an empty string for null/undefined input', () => {
    expect(parseReleaseQuality(null)).toBe('');
    expect(parseReleaseQuality(undefined)).toBe('');
  });
});
