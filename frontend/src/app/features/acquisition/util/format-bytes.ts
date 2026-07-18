const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/**
 * Human-readable byte size, e.g. `formatBytes(1536)` -> `"1.5 KB"`.
 * Mirrors the KB-based `formatFileSize` helper in
 * `metadata-viewer.component.ts`, but starts from raw bytes since that's
 * what the acquisition search endpoint returns (`ProwlarrReleaseDto.size`).
 */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || bytes < 0) {
    return '-';
  }
  if (bytes === 0) {
    return '0 B';
  }

  let size = bytes;
  let unitIndex = 0;

  while (size >= 1024 && unitIndex < UNITS.length - 1) {
    size /= 1024;
    unitIndex++;
  }

  const decimals = size >= 100 ? 0 : size >= 10 ? 1 : 2;
  return `${size.toFixed(decimals)} ${UNITS[unitIndex]}`;
}
