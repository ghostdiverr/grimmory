import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../core/config/api-config';
import {CreateWantedBookRequest, WantedBookDto} from '../model/wanted.model';

@Injectable({providedIn: 'root'})
export class WantedBookService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = `${API_CONFIG.BASE_URL}/api/v1/wanted`;

  list(): Observable<WantedBookDto[]> {
    return this.http.get<WantedBookDto[]>(this.apiUrl);
  }

  create(request: CreateWantedBookRequest): Observable<WantedBookDto> {
    return this.http.post<WantedBookDto>(this.apiUrl, request);
  }

  pause(id: number): Observable<WantedBookDto> {
    return this.http.patch<WantedBookDto>(`${this.apiUrl}/${id}/pause`, {});
  }

  resume(id: number): Observable<WantedBookDto> {
    return this.http.patch<WantedBookDto>(`${this.apiUrl}/${id}/resume`, {});
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
