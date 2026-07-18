export type AcquisitionCategory = 'BOOK' | 'AUDIOBOOK';

/**
 * A single indexed release returned by Prowlarr's aggregated search.
 * Mirrors the backend `ProwlarrReleaseDto` (see WP1/WP2 of the acquisition plan).
 */
export interface ProwlarrReleaseDto {
  guid: string;
  indexerId: number;
  indexerName: string;
  title: string;
  size: number;
  seeders: number | null;
  leechers: number | null;
  publishDate: string;
  downloadUrl: string;
  protocol: string;
  category: AcquisitionCategory;
}

/**
 * Payload sent to `POST /api/v1/acquisition/grab` — the chosen release plus
 * optional book/library context so the backend can correlate the resulting
 * job back to the book the user searched from.
 */
export interface GrabRequest extends ProwlarrReleaseDto {
  bookId?: number;
  libraryId?: number;
}

/**
 * Lifecycle states for an `AcquisitionJobEntity`, mirroring the backend enum
 * `AcquisitionJobEntity.Status`.
 */
export type AcquisitionJobStatus =
  | 'GRABBED'
  | 'WAITING_FOR_FILE'
  | 'NORMALIZING'
  | 'IMPORTING'
  | 'COMPLETED'
  | 'FAILED'
  | 'TIMED_OUT';

/**
 * The job returned after a successful grab, and the shape pushed over the
 * `/user/queue/acquisition-job-update` websocket topic. Mirrors the backend
 * `AcquisitionJobDto` (see `backend/.../model/dto/acquisition/AcquisitionJobDto.java`).
 */
export interface AcquisitionJobDto {
  id: number;
  bookId?: number;
  libraryId?: number;
  query?: string;
  category?: AcquisitionCategory;
  releaseTitle?: string;
  releaseGuid?: string;
  indexerId?: number;
  indexerName?: string;
  protocol?: string;
  sizeBytes?: number;
  status: AcquisitionJobStatus;
  errorMessage?: string;
  requestedByUserId?: number;
  grabbedAt?: string;
  completedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Seed data passed into the search dialog from wherever it's launched
 * (currently the book detail page).
 */
export interface AcquisitionSearchSeed {
  title?: string;
  author?: string;
  isbn?: string;
  category: AcquisitionCategory;
  bookId?: number;
}
