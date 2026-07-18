import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../../../core/config/api-config';

export interface ProwlarrConnectionTestResult {
  success: boolean;
  message: string;
  version: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class ProwlarrSettingsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v1/acquisition`;

  testConnection(): Observable<ProwlarrConnectionTestResult> {
    return this.http.post<ProwlarrConnectionTestResult>(`${this.baseUrl}/test-connection`, {});
  }
}
