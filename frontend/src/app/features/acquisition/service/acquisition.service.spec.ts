import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {AcquisitionService} from './acquisition.service';
import {GrabRequest, ProwlarrReleaseDto} from '../model/acquisition.model';

describe('AcquisitionService', () => {
  let service: AcquisitionService;
  let httpTestingController: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        AcquisitionService,
      ],
    });

    service = TestBed.inject(AcquisitionService);
    httpTestingController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTestingController.verify();
    TestBed.resetTestingModule();
  });

  it('requests a search with the query and category as URL params', () => {
    service.search('Dune', 'BOOK').subscribe();

    const request = httpTestingController.expectOne(
      req => req.url === 'http://localhost:6060/api/v1/acquisition/search'
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('query')).toBe('Dune');
    expect(request.request.params.get('category')).toBe('BOOK');
    request.flush([]);
  });

  it('posts a grab request with the release payload as the body', () => {
    const release: ProwlarrReleaseDto = {
      guid: 'guid-1',
      indexerId: 1,
      indexerName: 'MyIndexer',
      title: 'Dune - Frank Herbert [EPUB]',
      size: 1048576,
      seeders: 12,
      leechers: 2,
      publishDate: '2026-01-01T00:00:00Z',
      downloadUrl: 'https://example.com/download/guid-1',
      protocol: 'torrent',
      category: 'BOOK',
    };
    const request: GrabRequest = {...release, bookId: 42};

    service.grab(request).subscribe();

    const httpRequest = httpTestingController.expectOne('http://localhost:6060/api/v1/acquisition/grab');
    expect(httpRequest.request.method).toBe('POST');
    expect(httpRequest.request.body).toEqual(request);
    httpRequest.flush({id: 1, status: 'QUEUED'});
  });
});
