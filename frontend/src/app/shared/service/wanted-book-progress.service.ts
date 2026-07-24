import {computed, effect, inject, Injectable, OnDestroy} from '@angular/core';
import {BehaviorSubject, Subscription} from 'rxjs';
import {toSignal} from '@angular/core/rxjs-interop';
import {WantedBookDto} from '../../features/wanted/model/wanted.model';
import {WantedBookService} from '../../features/wanted/service/wanted-book.service';
import {UserService} from '../../features/settings/user-management/user.service';

@Injectable({providedIn: 'root'})
export class WantedBookProgressService implements OnDestroy {
  private entryMap = new Map<number, BehaviorSubject<WantedBookDto>>();

  private activeEntriesSubject = new BehaviorSubject<Record<number, WantedBookDto>>({});
  activeEntries$ = this.activeEntriesSubject.asObservable();

  private readonly activeEntriesSignal = toSignal(this.activeEntries$, {initialValue: {}});
  readonly entries = computed(() => Object.values(this.activeEntriesSignal()));

  private wantedBookService = inject(WantedBookService);
  private userService = inject(UserService);

  private subscriptions = new Subscription();
  private hasInitialized = false;

  constructor() {
    effect(() => {
      const user = this.userService.currentUser();
      if (this.hasInitialized || !user) {
        return;
      }
      this.hasInitialized = true;

      const listSub = this.wantedBookService.list().subscribe({
        next: (entries) => this.initializeEntries(entries),
        error: (err) => console.warn('Failed to fetch wanted list entries:', err)
      });
      this.subscriptions.add(listSub);
    });
  }

  handleIncomingUpdate(entry: WantedBookDto): void {
    if (!this.entryMap.has(entry.id)) {
      this.entryMap.set(entry.id, new BehaviorSubject(entry));
    } else {
      this.entryMap.get(entry.id)!.next(entry);
    }

    this.activeEntriesSubject.next(this.getActiveEntries());
  }

  removeEntry(id: number): void {
    this.entryMap.delete(id);
    this.activeEntriesSubject.next(this.getActiveEntries());
  }

  getActiveEntries(): Record<number, WantedBookDto> {
    const result: Record<number, WantedBookDto> = {};
    this.entryMap.forEach((subject, id) => {
      result[id] = subject.getValue();
    });
    return result;
  }

  private initializeEntries(entries: WantedBookDto[]): void {
    for (const entry of entries) {
      this.entryMap.set(entry.id, new BehaviorSubject(entry));
    }
    this.activeEntriesSubject.next(this.getActiveEntries());
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.entryMap.forEach(subject => subject.complete());
    this.activeEntriesSubject.complete();
  }
}
