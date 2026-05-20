import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {TranslocoService} from '@jsverse/transloco';

import {LanguageService, LanguageOption} from './language.service';

describe('LanguageService', () => {
  let service: LanguageService;
  let httpTesting: HttpTestingController;
  const getActiveLang = vi.fn(() => 'en');

  beforeEach(() => {
    getActiveLang.mockReturnValue('en');

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        LanguageService,
        {provide: TranslocoService, useValue: {getActiveLang}},
      ],
    });

    service = TestBed.inject(LanguageService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('fetches languages from the backend and emits the result', () => {
    const expected: LanguageOption[] = [{code: 'fr', name: 'French'}, {code: 'en', name: 'English'}];
    let result: LanguageOption[] | undefined;

    service.getLanguages().subscribe(langs => { result = langs; });

    httpTesting.expectOne(req => req.url.includes('/api/v1/languages') && req.url.includes('lang=en')).flush(expected);

    expect(result).toEqual(expected);
  });

  it('returns the cached observable on repeated calls for the same locale', () => {
    const obs1 = service.getLanguages();
    const obs2 = service.getLanguages();

    obs1.subscribe();
    httpTesting.expectOne(req => req.url.includes('lang=en')).flush([]);

    obs2.subscribe();

    expect(obs1).toBe(obs2);
  });

  it('issues a new request for a different locale', () => {
    service.getLanguages().subscribe();
    httpTesting.expectOne(req => req.url.includes('lang=en')).flush([]);

    getActiveLang.mockReturnValue('fr');
    service.getLanguages().subscribe();
    httpTesting.expectOne(req => req.url.includes('lang=fr')).flush([]);
  });
});
