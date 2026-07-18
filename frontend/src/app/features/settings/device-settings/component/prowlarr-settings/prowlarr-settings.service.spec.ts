import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {ProwlarrSettingsService} from './prowlarr-settings.service';

describe('ProwlarrSettingsService', () => {
  let service: ProwlarrSettingsService;
  let httpTestingController: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ProwlarrSettingsService,
      ],
    });

    service = TestBed.inject(ProwlarrSettingsService);
    httpTestingController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTestingController.verify();
    TestBed.resetTestingModule();
  });

  it('posts to the test-connection endpoint and returns the result', () => {
    let responseBody: unknown;

    service.testConnection().subscribe(response => {
      responseBody = response;
    });

    const request = httpTestingController.expectOne(req =>
      req.method === 'POST' && req.url.endsWith('/api/v1/acquisition/test-connection')
    );
    request.flush({success: true, message: 'Connected to Prowlarr v1.2.3', version: '1.2.3'});

    expect(responseBody).toEqual({
      success: true,
      message: 'Connected to Prowlarr v1.2.3',
      version: '1.2.3',
    });
  });

  it('surfaces a failed connection test result without throwing', () => {
    let responseBody: unknown;

    service.testConnection().subscribe(response => {
      responseBody = response;
    });

    const request = httpTestingController.expectOne(req =>
      req.method === 'POST' && req.url.endsWith('/api/v1/acquisition/test-connection')
    );
    request.flush({success: false, message: 'Unable to reach Prowlarr', version: null});

    expect(responseBody).toEqual({
      success: false,
      message: 'Unable to reach Prowlarr',
      version: null,
    });
  });
});
