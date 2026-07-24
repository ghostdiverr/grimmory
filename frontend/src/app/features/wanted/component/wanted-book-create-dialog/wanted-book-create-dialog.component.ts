import {Component, OnDestroy, computed, inject, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {DynamicDialogRef} from 'primeng/dynamicdialog';
import {Button} from 'primeng/button';
import {InputText} from 'primeng/inputtext';
import {Select} from 'primeng/select';
import {SelectButton} from 'primeng/selectbutton';
import {MessageService} from 'primeng/api';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {Subject, takeUntil} from 'rxjs';
import {WantedBookService} from '../../service/wanted-book.service';
import {CreateWantedBookRequest, WantedBookDto} from '../../model/wanted.model';
import {AcquisitionCategory} from '../../../acquisition/model/acquisition.model';
import {LibraryService} from '../../../book/service/library.service';
import {BookMetadataService} from '../../../book/service/book-metadata.service';
import {BookMetadata} from '../../../book/model/book.model';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {FetchMetadataRequest} from '../../../metadata/model/request/fetch-metadata-request.model';
import {MetadataProviderSettings} from '../../../../shared/model/app-settings.model';

interface CategoryOption {
  label: string;
  value: AcquisitionCategory;
}

interface LibraryOption {
  label: string;
  value: number;
}

@Component({
  selector: 'app-wanted-book-create-dialog',
  standalone: true,
  templateUrl: './wanted-book-create-dialog.component.html',
  styleUrl: './wanted-book-create-dialog.component.scss',
  imports: [FormsModule, Button, InputText, Select, SelectButton, TranslocoDirective],
})
export class WantedBookCreateDialogComponent implements OnDestroy {
  private readonly dialogRef = inject(DynamicDialogRef);
  private readonly wantedBookService = inject(WantedBookService);
  private readonly libraryService = inject(LibraryService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);
  private readonly bookMetadataService = inject(BookMetadataService);
  private readonly appSettingsService = inject(AppSettingsService);

  private readonly cancelSearch$ = new Subject<void>();

  readonly categoryOptions: CategoryOption[] = [
    {label: this.t.translate('wanted.categoryBook'), value: 'BOOK'},
    {label: this.t.translate('wanted.categoryAudiobook'), value: 'AUDIOBOOK'},
  ];

  readonly libraryOptions = computed<LibraryOption[]>(() =>
    this.libraryService.libraries()
      .filter(library => library.id != null)
      .map(library => ({label: library.name, value: library.id!}))
  );

  readonly title = signal('');
  readonly author = signal('');
  readonly isbn = signal('');
  readonly category = signal<AcquisitionCategory>('BOOK');
  readonly libraryId = signal<number | null>(null);
  readonly submitting = signal(false);

  readonly canSubmit = computed(() =>
    this.title().trim().length > 0 && this.libraryId() != null && !this.submitting()
  );

  readonly enabledProviders = computed<string[]>(() => {
    const settings = this.appSettingsService.appSettings();
    const providerSettings = settings?.metadataProviderSettings ?? ({} as MetadataProviderSettings);
    return Object.entries(providerSettings)
      .filter(([, value]) => this.isEnabledProviderSetting(value) && value.enabled)
      .map(([key]) => key.charAt(0).toUpperCase() + key.slice(1));
  });

  readonly canSearch = computed(() =>
    this.enabledProviders().length > 0
    && (this.title().trim().length > 0 || this.isbn().trim().length > 0)
    && !this.searching()
  );

  readonly searching = signal(false);
  readonly searchTriggered = signal(false);
  readonly searchResults = signal<BookMetadata[]>([]);

  private isEnabledProviderSetting(value: unknown): value is { enabled: boolean } {
    return !!value && typeof value === 'object' && 'enabled' in value;
  }

  search(): void {
    if (!this.canSearch()) {
      return;
    }

    this.cancelSearch$.next();
    this.searching.set(true);
    this.searchTriggered.set(true);
    this.searchResults.set([]);

    const request: FetchMetadataRequest = {
      providers: this.enabledProviders(),
      title: this.title().trim() || undefined,
      author: this.author().trim() || undefined,
      isbn: this.isbn().trim() || undefined,
    };

    this.bookMetadataService.searchMetadata(request)
      .pipe(takeUntil(this.cancelSearch$))
      .subscribe({
        next: (metadata) => this.searchResults.update(results => [...results, metadata]),
        error: () => this.searching.set(false),
        complete: () => this.searching.set(false),
      });
  }

  pickSearchResult(metadata: BookMetadata): void {
    this.title.set(metadata.title ?? this.title());
    this.author.set(metadata.authors?.[0] ?? this.author());
    this.isbn.set(metadata.isbn13 ?? metadata.isbn10 ?? this.isbn());
    this.searchResults.set([]);
    this.searchTriggered.set(false);
  }

  ngOnDestroy(): void {
    this.cancelSearch$.next();
    this.cancelSearch$.complete();
  }

  submit(): void {
    if (!this.canSubmit()) {
      return;
    }

    const request: CreateWantedBookRequest = {
      title: this.title().trim(),
      author: this.author().trim() || undefined,
      isbn: this.isbn().trim() || undefined,
      category: this.category(),
      libraryId: this.libraryId()!,
    };

    this.submitting.set(true);

    this.wantedBookService.create(request).subscribe({
      next: (entry: WantedBookDto) => {
        this.submitting.set(false);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('wanted.toast.createSuccessSummary'),
          detail: this.t.translate('wanted.toast.createSuccessDetail', {title: entry.title}),
        });
        this.dialogRef.close(entry);
      },
      error: () => {
        this.submitting.set(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('wanted.toast.createErrorSummary'),
          detail: this.t.translate('wanted.toast.createErrorDetail'),
        });
      },
    });
  }

  close(): void {
    this.dialogRef.close();
  }
}
