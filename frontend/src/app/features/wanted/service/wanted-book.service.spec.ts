import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {WantedBookService} from './wanted-book.service';
import {CreateWantedBookRequest, WantedBookDto} from '../model/wanted.model';

describe('WantedBookService', () => {
  let service: WantedBookService;
  let httpTestingController: HttpTestingController;

  const entry: WantedBookDto = {
    id: 1,
    title: 'Dune',
    author: 'Frank Herbert',
    category: 'BOOK',
    libraryId: 3,
    status: 'ACTIVE',
    requestedByUserId: 7,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        WantedBookService,
      ],
    });

    service = TestBed.inject(WantedBookService);
    httpTestingController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTestingController.verify();
    TestBed.resetTestingModule();
  });

  it('requests the wanted list', () => {
    service.list().subscribe();

    const request = httpTestingController.expectOne('http://localhost:6060/api/v1/wanted');
    expect(request.request.method).toBe('GET');
    request.flush([entry]);
  });

  it('posts a create request with the payload as the body', () => {
    const request: CreateWantedBookRequest = {
      title: 'Dune',
      author: 'Frank Herbert',
      category: 'BOOK',
      libraryId: 3,
    };

    service.create(request).subscribe();

    const httpRequest = httpTestingController.expectOne('http://localhost:6060/api/v1/wanted');
    expect(httpRequest.request.method).toBe('POST');
    expect(httpRequest.request.body).toEqual(request);
    httpRequest.flush(entry);
  });

  it('patches pause for an entry', () => {
    service.pause(1).subscribe();

    const request = httpTestingController.expectOne('http://localhost:6060/api/v1/wanted/1/pause');
    expect(request.request.method).toBe('PATCH');
    request.flush({...entry, status: 'PAUSED'});
  });

  it('patches resume for an entry', () => {
    service.resume(1).subscribe();

    const request = httpTestingController.expectOne('http://localhost:6060/api/v1/wanted/1/resume');
    expect(request.request.method).toBe('PATCH');
    request.flush({...entry, status: 'ACTIVE'});
  });

  it('deletes an entry', () => {
    service.delete(1).subscribe();

    const request = httpTestingController.expectOne('http://localhost:6060/api/v1/wanted/1');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
  });
});
