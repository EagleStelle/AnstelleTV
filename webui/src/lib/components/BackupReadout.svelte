<script lang="ts">
	import type { Backup } from '#lib/api.ts';
	import { ago } from '#lib/format.ts';
	import Dns from '~icons/material-symbols/dns';
	import Folder from '~icons/material-symbols/folder';
	import InsertChart from '~icons/material-symbols/insert-chart';
	import Fact from './Fact.svelte';
	import Facts from './Facts.svelte';
	import Figure from './Figure.svelte';
	import Figures from './Figures.svelte';
	import Readout from './Readout.svelte';
	import Rule from './Rule.svelte';
	import StatusLine from './StatusLine.svelte';

	/**
	 * What a backup of the TV holds, the way the other readouts read: when one was last taken as the
	 * status line, channels, profiles and custom themes as figures, then its servers, groups and the
	 * dashboards' days.
	 */
	let { backup }: { backup: Backup } = $props();
</script>

<Readout>
	{#if backup.savedUtcMs}
		<StatusLine label="Saved" text={ago(backup.savedUtcMs)} />
	{:else}
		<StatusLine label="Not saved yet" state="off" />
	{/if}
	<Figures>
		<Figure value={backup.channels} name={backup.channels === 1 ? 'channel' : 'channels'} lead />
		<Figure value={backup.profiles} name={backup.profiles === 1 ? 'profile' : 'profiles'} />
		<Figure value={backup.themes} name={backup.themes === 1 ? 'custom theme' : 'custom themes'} />
	</Figures>
	<Rule />
	<Facts>
		<Fact icon={Dns} text={String(backup.servers)} detail={backup.servers === 1 ? 'server' : 'servers'} />
		<Fact icon={Folder} text={String(backup.groups)} detail={backup.groups === 1 ? 'group' : 'groups'} />
		<Fact icon={InsertChart} text={String(backup.historyDays)} detail={backup.historyDays === 1 ? 'day of history' : 'days of history'} />
	</Facts>
</Readout>
