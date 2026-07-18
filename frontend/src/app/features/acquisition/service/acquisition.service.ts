import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../core/config/api-config';
import {AcquisitionCategory, AcquisitionJobDto, GrabRequest, ProwlarrReleaseDto} from '../model/acquisition.model';

/**
 * Thin HTTP wrapper around the backend acquisition endpoints (WP1/WP2).
 *
 * `search` is a plain HttpClient call rather than `injectQuery`: it's fired
 * once per explicit user click on the search dialog's "Search" button, not a
 * reactive read that should auto-refetch on signal changes, so the extra
 * cache/observer machinery `injectQuery` brings wouldn't add value here.
 */
@Injectable({providedIn: 'root'})
export class AcquisitionService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = `${API_CONFIG.BASE_URL}/api/v1/acquisition`;

  search(query: string, category: AcquisitionCategory): Observable<ProwlarrReleaseDto[]> {
    const params = new HttpParams()
      .set('query', query)
      .set('category', category);

    return this.http.get<ProwlarrReleaseDto[]>(`${this.apiUrl}/search`, {params});
  }

  grab(request: GrabRequest): Observable<AcquisitionJobDto> {
    return this.http.post<AcquisitionJobDto>(`${this.apiUrl}/grab`, request);
  }

  listJobs(): Observable<AcquisitionJobDto[]> {
    return this.http.get<AcquisitionJobDto[]>(`${this.apiUrl}/jobs`);
  }

  cancelJob(id: number): Observable<AcquisitionJobDto> {
    return this.http.delete<AcquisitionJobDto>(`${this.apiUrl}/jobs/${id}`);
  }
}
