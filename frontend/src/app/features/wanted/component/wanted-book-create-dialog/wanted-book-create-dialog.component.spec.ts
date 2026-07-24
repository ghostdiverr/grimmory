import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {DynamicDialogRef} from 'primeng/dynamicdialog';
import {MessageService} from 'primeng/api';
import {TranslocoService} from '@jsverse/transloco';

import {WantedBookService} from '../../service/wanted-book.service';
import {WantedBookDto} from '../../model/wanted.model';
import {LibraryService} from '../../../book/service/library.service';
import {BookMetadataService} from '../../../book/service/book-metadata.service';
import {BookMetadata} from '../../../book/model/book.model';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {WantedBookCreateDialogComponent} from './wanted-book-create-dialog.component';

describe('WantedBookCreateDialogComponent', () => {
  const close = vi.fn();
  const create = vi.fn();
  const messageAdd = vi.fn();
  const translate = vi.fn((key: string) => key);
  const searchMetadata = vi.fn();

  const libraries = signal([
    {id: 1, name: 'Fiction'},
    {id: 2, name: 'Non-Fiction'},
  ]);

  const appSettings = signal({
    metadataProviderSettings: {
      google: {enabled: true},
      amazon: {enabled: false},
    },
  });

  const entry: WantedBookDto = {
    id: 1,
    title: 'Dune',
    category: 'BOOK',
    libraryId: 1,
    status: 'ACTIVE',
    requestedByUserId: 7,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(() => {
    close.mockClear();
    create.mockReset();
    messageAdd.mockClear();
    translate.mockClear();
    searchMetadata.mockReset();
    appSettings.set({
      metadataProviderSettings: {
        google: {enabled: true},
        amazon: {enabled: false},
      },
    });

    TestBed.configureTestingModule({
      providers: [
        {provide: DynamicDialogRef, useValue: {close}},
        {provide: WantedBookService, useValue: {create}},
        {provide: LibraryService, useValue: {libraries}},
        {provide: MessageService, useValue: {add: messageAdd}},
        {provide: TranslocoService, useValue: {translate}},
        {provide: BookMetadataService, useValue: {searchMetadata}},
        {provide: AppSettingsService, useValue: {appSettings}},
      ],
    });
  });

  function createComponent(): WantedBookCreateDialogComponent {
    return TestBed.runInInjectionContext(() => new WantedBookCreateDialogComponent());
  }

  it('derives library options from the library service', () => {
    const component = createComponent();

    expect(component.libraryOptions()).toEqual([
      {label: 'Fiction', value: 1},
      {label: 'Non-Fiction', value: 2},
    ]);
  });

  it('cannot submit without a title or a library', () => {
    const component = createComponent();

    expect(component.canSubmit()).toBe(false);

    component.title.set('Dune');
    expect(component.canSubmit()).toBe(false);

    component.libraryId.set(1);
    expect(component.canSubmit()).toBe(true);
  });

  it('does not submit blank titles', () => {
    const component = createComponent();
    component.title.set('   ');
    component.libraryId.set(1);

    expect(component.canSubmit()).toBe(false);
  });

  it('submits a trimmed payload and closes the dialog on success', () => {
    create.mockReturnValue(of(entry));
    const component = createComponent();

    component.title.set('  Dune  ');
    component.author.set('  Frank Herbert  ');
    component.isbn.set('  ');
    component.libraryId.set(1);
    component.submit();

    expect(create).toHaveBeenCalledWith({
      title: 'Dune',
      author: 'Frank Herbert',
      isbn: undefined,
      category: 'BOOK',
      libraryId: 1,
    });
    expect(close).toHaveBeenCalledWith(entry);
    expect(messageAdd).toHaveBeenCalledWith(expect.objectContaining({severity: 'success'}));
    expect(component.submitting()).toBe(false);
  });

  it('shows an error toast and stops submitting when the create request fails', () => {
    create.mockReturnValue(throwError(() => new Error('failed')));
    const component = createComponent();

    component.title.set('Dune');
    component.libraryId.set(1);
    component.submit();

    expect(close).not.toHaveBeenCalled();
    expect(component.submitting()).toBe(false);
    expect(messageAdd).toHaveBeenCalledWith(expect.objectContaining({severity: 'error'}));
  });

  it('does not submit when the form is invalid', () => {
    const component = createComponent();

    component.submit();

    expect(create).not.toHaveBeenCalled();
  });

  it('closes the dialog without saving', () => {
    const component = createComponent();

    component.close();

    expect(close).toHaveBeenCalledOnce();
  });

  it('derives enabled providers from app settings', () => {
    const component = createComponent();

    expect(component.enabledProviders()).toEqual(['Google']);
  });

  it('cannot search without a title/isbn or without enabled providers', () => {
    const component = createComponent();

    expect(component.canSearch()).toBe(false);

    component.title.set('Dune');
    expect(component.canSearch()).toBe(true);

    appSettings.set({metadataProviderSettings: {google: {enabled: false}, amazon: {enabled: false}}});
    expect(component.canSearch()).toBe(false);
  });

  it('searches metadata providers and fills the form from the picked result', () => {
    const result: BookMetadata = {
      bookId: 0,
      title: 'Dune',
      authors: ['Frank Herbert'],
      isbn13: '9780441013593',
    };
    searchMetadata.mockReturnValue(of(result));
    const component = createComponent();

    component.title.set('Dune');
    component.search();

    expect(searchMetadata).toHaveBeenCalledWith({
      providers: ['Google'],
      title: 'Dune',
      author: undefined,
      isbn: undefined,
    });
    expect(component.searchResults()).toEqual([result]);
    expect(component.searching()).toBe(false);

    component.pickSearchResult(result);

    expect(component.title()).toBe('Dune');
    expect(component.author()).toBe('Frank Herbert');
    expect(component.isbn()).toBe('9780441013593');
    expect(component.searchResults()).toEqual([]);
  });

  it('shows no results once the search completes empty-handed', () => {
    searchMetadata.mockReturnValue(of());
    const component = createComponent();

    component.title.set('Dune');
    component.search();

    expect(component.searchTriggered()).toBe(true);
    expect(component.searchResults()).toEqual([]);
  });

  it('stops searching when the request fails', () => {
    searchMetadata.mockReturnValue(throwError(() => new Error('boom')));
    const component = createComponent();

    component.title.set('Dune');
    component.search();

    expect(component.searching()).toBe(false);
    expect(component.searchResults()).toEqual([]);
  });
});
