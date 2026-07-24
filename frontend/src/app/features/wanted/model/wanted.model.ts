import {AcquisitionCategory} from '../../acquisition/model/acquisition.model';

/**
 * Lifecycle states for a `WantedBookEntity`, mirroring the backend enum
 * `WantedBookEntity.Status`.
 */
export type WantedBookStatus = 'ACTIVE' | 'GRABBED' | 'FULFILLED' | 'PAUSED';

/**
 * A free-form wanted-list entry — not tied to an existing book — periodically
 * searched for on Prowlarr and auto-grabbed when a matching release appears.
 * Mirrors the backend `WantedBookDto`.
 */
export interface WantedBookDto {
  id: number;
  title: string;
  author?: string;
  isbn?: string;
  category: AcquisitionCategory;
  libraryId: number;
  status: WantedBookStatus;
  lastSearchedAt?: string;
  lastResultCount?: number;
  acquisitionJobId?: number;
  requestedByUserId: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * Payload sent to `POST /api/v1/wanted` to add a new wanted-list entry.
 */
export interface CreateWantedBookRequest {
  title: string;
  author?: string;
  isbn?: string;
  category: AcquisitionCategory;
  libraryId: number;
}
