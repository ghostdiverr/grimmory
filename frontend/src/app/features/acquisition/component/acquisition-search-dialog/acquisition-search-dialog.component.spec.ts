import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {MessageService} from 'primeng/api';
import {TranslocoService} from '@jsverse/transloco';

import {AcquisitionService} from '../../service/acquisition.service';
import {AcquisitionSearchSeed, ProwlarrReleaseDto} from '../../model/acquisition.model';
import {AcquisitionSearchDialogComponent} from './acquisition-search-dialog.component';

describe('AcquisitionSearchDialogComponent', () => {
  const close = vi.fn();
  const search = vi.fn();
  const grab = vi.fn();
  const messageAdd = vi.fn();
  const translate = vi.fn((key: string) => key);

  beforeEach(() => {
    close.mockClear();
    search.mockReset();
    grab.mockReset();
    messageAdd.mockClear();
    translate.mockClear();
  });

  function createRelease(overrides: Partial<ProwlarrReleaseDto> = {}): ProwlarrReleaseDto {
    return {
      guid: 'guid-1',
      indexerId: 1,
      indexerName: 'MyIndexer',
      title: 'Dune - Frank Herbert [EPUB]',
      size: 1048576,
      seeders: 10,
      leechers: 1,
      publishDate: '2026-01-01T00:00:00Z',
      downloadUrl: 'https://example.com/download/guid-1',
      protocol: 'torrent',
      category: 'BOOK',
      ...overrides,
    };
  }

  function createComponent(seed: AcquisitionSearchSeed = {category: 'BOOK'}) {
    TestBed.configureTestingModule({
      providers: [
        {provide: DynamicDialogConfig, useValue: {data: seed}},
        {provide: DynamicDialogRef, useValue: {close}},
        {provide: AcquisitionService, useValue: {search, grab}},
        {provide: MessageService, useValue: {add: messageAdd}},
        {provide: TranslocoService, useValue: {translate}},
      ],
    });

    return TestBed.runInInjectionContext(() => new AcquisitionSearchDialogComponent());
  }

  it('defaults the results table sort to seeders descending', () => {
    const component = createComponent();

    expect(component.sortField).toBe('seeders');
    expect(component.sortOrder).toBe(-1);
  });

  it('pre-fills the query from the seed title/author and auto-searches on init', () => {
    search.mockReturnValue(of([createRelease()]));

    const component = createComponent({title: 'Dune', author: 'Frank Herbert', category: 'BOOK'});

    expect(component.query()).toBe('Dune Frank Herbert');

    component.ngOnInit();

    expect(search).toHaveBeenCalledWith('Dune Frank Herbert', 'BOOK');
    expect(component.results()).toEqual([createRelease()]);
    expect(component.loading()).toBe(false);
  });

  it('does not auto-search when the seed has no title or author', () => {
    const component = createComponent({category: 'BOOK'});

    component.ngOnInit();

    expect(search).not.toHaveBeenCalled();
    expect(component.searched()).toBe(false);
  });

  it('populates results on a manual search and shows an error toast on failure', () => {
    const component = createComponent({category: 'BOOK'});
    component.onQueryChange('Dune');

    search.mockReturnValueOnce(of([createRelease()]));
    component.onSearch();
    expect(component.results()).toEqual([createRelease()]);
    expect(component.searched()).toBe(true);

    search.mockReturnValueOnce(throwError(() => new Error('network error')));
    component.onSearch();
    expect(component.results()).toEqual([]);
    expect(messageAdd).toHaveBeenCalledWith(expect.objectContaining({severity: 'error'}));
  });

  it('grabs a release, sending the seed bookId, and marks it grabbed on success', () => {
    const release = createRelease();
    grab.mockReturnValue(of({id: 1, status: 'QUEUED'}));

    const component = createComponent({category: 'BOOK', bookId: 42});
    component.grab(release);

    expect(grab).toHaveBeenCalledWith({...release, bookId: 42});
    expect(component.isGrabbed(release)).toBe(true);
    expect(component.isGrabbing(release)).toBe(false);
    expect(messageAdd).toHaveBeenCalledWith(expect.objectContaining({severity: 'success'}));
  });

  it('shows an error toast and leaves the release ungrabbed when the grab request fails', () => {
    const release = createRelease();
    grab.mockReturnValue(throwError(() => new Error('failed')));

    const component = createComponent({category: 'BOOK', bookId: 42});
    component.grab(release);

    expect(component.isGrabbed(release)).toBe(false);
    expect(component.isGrabbing(release)).toBe(false);
    expect(messageAdd).toHaveBeenCalledWith(expect.objectContaining({severity: 'error'}));
  });

  it('does not re-grab a release that is already grabbed', () => {
    const release = createRelease();
    grab.mockReturnValue(of({id: 1, status: 'QUEUED'}));

    const component = createComponent({category: 'BOOK', bookId: 42});
    component.grab(release);
    component.grab(release);

    expect(grab).toHaveBeenCalledOnce();
  });

  it('closes the dialog', () => {
    const component = createComponent();

    component.close();

    expect(close).toHaveBeenCalledOnce();
  });
});
