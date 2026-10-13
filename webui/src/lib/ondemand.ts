// On Demand's tabs and rows as the app's settings mark them.

import type { Component } from 'svelte';
import type { CatalogKey, Shelf } from './api.ts';
import History from '~icons/material-symbols/history';
import Movie from '~icons/material-symbols/movie';
import NewReleases from '~icons/material-symbols/new-releases';
import SkipNext from '~icons/material-symbols/skip-next';
import Tv from '~icons/material-symbols/tv';

/** A catalog tab's mark. */
export function kindIcon(kind: CatalogKey): Component<any> {
	return kind === 'movies' ? Movie : Tv;
}

/** A fixed row's mark. */
export function shelfIcon(type: Shelf['type']): Component<any> {
	switch (type) {
		case 'continue':
			return History;
		case 'nextup':
			return SkipNext;
		case 'recent':
			return NewReleases;
		default:
			return Tv;
	}
}
