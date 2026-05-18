import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {map, shareReplay} from 'rxjs/operators';
import {API_CONFIG} from '../../core/config/api-config';
import {TranslocoService} from '@jsverse/transloco';

export interface LanguageOption {
  code: string;
  name: string;
}

@Injectable({
  providedIn: 'root'
})
export class LanguageService {
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v1/languages`;

  private http = inject(HttpClient);
  private t = inject(TranslocoService);

  private cache = new Map<string, Observable<LanguageOption[]>>();

  getLanguages(): Observable<LanguageOption[]> {
    const lang = this.t.getActiveLang();
    if (!this.cache.has(lang)) {
      const request$ = this.http.get<LanguageOption[]>(`${this.baseUrl}?lang=${lang}`).pipe(
        shareReplay(1)
      );
      this.cache.set(lang, request$);
    }
    return this.cache.get(lang)!;
  }
}
