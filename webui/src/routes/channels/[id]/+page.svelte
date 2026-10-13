<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, ApiError, problem, type ChannelDetail, type ChannelPatch, type Group, type Playback } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Buttons from '#lib/components/Buttons.svelte';
	import ChoiceRow from '#lib/components/ChoiceRow.svelte';
	import DangerButton from '#lib/components/DangerButton.svelte';
	import LinkRow from '#lib/components/LinkRow.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Readout from '#lib/components/Readout.svelte';
	import Row from '#lib/components/Row.svelte';
	import Section from '#lib/components/Section.svelte';
	import StatusLine from '#lib/components/StatusLine.svelte';
	import SwitchRow from '#lib/components/SwitchRow.svelte';
	import TextRow from '#lib/components/TextRow.svelte';
	import { time } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import Folder from '~icons/material-symbols/folder';
	import HideImage from '~icons/material-symbols/hide-image';
	import Image from '~icons/material-symbols/image';
	import Movie from '~icons/material-symbols/movie';
	import Star from '~icons/material-symbols/star';
	import StarOutline from '~icons/material-symbols/star-outline';
	import Title from '~icons/material-symbols/title';
	import Tune from '~icons/material-symbols/tune';
	import Tv from '~icons/material-symbols/tv';
	import Upload from '~icons/material-symbols/upload';
	import Visibility from '~icons/material-symbols/visibility';
	import VisibilityOff from '~icons/material-symbols/visibility-off';
	import { onMount } from 'svelte';

	const id = $derived(Number(page.params.id));
	let detail = $state<ChannelDetail | null>(null);
	let groups = $state<Group[]>([]);
	let error = $state<string | null>(null);
	let message = $state<string | null>(null);
	let gone = $state(false);
	let picker = $state<HTMLInputElement>();

	async function load() {
		try {
			const [channel, groupList] = await Promise.all([api.channel(id), api.groups()]);
			detail = channel;
			groups = groupList.groups;
			error = null;
		} catch (e) {
			if (e instanceof ApiError && e.status === 404) gone = true;
			else error = problem(e);
		}
	}

	$effect(() => {
		if (id) void load();
	});

	onMount(() => {
		// Schedules are written in the background after edits, so what airs is read again now and then.
		const timer = setInterval(() => void load(), 15000);
		const off = live.subscribe(['channel', 'channel_group', 'channel_rule', 'schedule_slot'], () => void load());
		return () => {
			clearInterval(timer);
			off();
		};
	});

	async function change(patch: ChannelPatch) {
		try {
			await api.updateChannel(id, patch);
			error = null;
			await load();
		} catch (e) {
			error = problem(e);
		}
	}

	async function upload(event: Event) {
		const file = (event.currentTarget as HTMLInputElement).files?.[0];
		if (!file) return;
		try {
			await api.setLogo(id, file);
			message = 'Logo saved.';
			error = null;
			await load();
		} catch (e) {
			error = problem(e);
		} finally {
			if (picker) picker.value = '';
		}
	}

	async function clearLogo() {
		try {
			await api.clearLogo(id);
			message = null;
			await load();
		} catch (e) {
			error = problem(e);
		}
	}

	async function remove() {
		try {
			await api.deleteChannel(id);
			await goto('/channels/custom');
		} catch (e) {
			error = problem(e);
		}
	}

	const samePlayback = (a: Playback, b: Playback) => a.order === b.order && a.blockSize === b.blockSize;
	const custom = $derived(groups.filter((g) => !g.fromLibrary));
	const trail = [
		{ label: 'Home', href: '/' },
		{ label: 'Live TV', href: '/channels' }
	];
</script>

{#if gone}
	<Page title="Channel" {trail}><Notice text="This channel is gone." /></Page>
{:else if detail}
	{@const channel = detail.channel}
	{@const series = detail.seriesChoices.find((c) => samePlayback(c.playback, detail!.series))}
	{@const movies = detail.movieChoices.find((c) => samePlayback(c.playback, detail!.movies))}
	{@const oncePerDay = detail.fitsOncePerDay && detail.oncePerDay}
	<Page
		title="{channel.number}  {channel.name}"
		{trail}
		note={channel.fromLibrary
			? undefined
			: 'Movies can air once daily: each at most once in 24 hours, when series or a full day of movies fill the time. Nothing airs twice in a row while anything else can.'}
	>
		<!-- What airs now, next and later, as the banner's status lines side by side, after the channel's logo. -->
		{#snippet overview()}
			<Readout>
				<div class="flex flex-wrap items-center gap-x-10 gap-y-5">
					{#if channel.logoUrl}<img src={channel.logoUrl} alt="{channel.name} logo" class="h-15 w-25 shrink-0 object-contain object-left" />{/if}
					{#each detail!.airings as airing, i (airing.startUtcMs)}
						<div class="flex min-w-0 flex-1 basis-44 flex-col gap-1">
							{#if i === 0}
								<StatusLine label={airing.title ? 'Now' : 'Off air'} text="until {time(airing.endUtcMs)}" />
							{:else}
								<StatusLine label={i === 1 ? 'Next' : 'Later'} text={time(airing.startUtcMs)} state="off" />
							{/if}
							{#if airing.title}<p class={['truncate font-semibold', i === 0 ? 'text-title-l' : 'text-title-m']}>{airing.title}</p>{/if}
						</div>
					{/each}
					{#if detail!.airings.length === 0}<p class="text-body-m text-text-muted">No schedule yet.</p>{/if}
				</div>
			</Readout>
		{/snippet}

		{#if error}<Notice text={error} error />{/if}
		{#if message}<Notice text={message} />{/if}
		{#if !channel.fromLibrary}
			<LinkRow title="Lineup" summary={detail.summary} icon={Tune} href="/channels/{id}/lineup" />
			<ChoiceRow
				title="Group"
				value={custom.find((g) => g.id === channel.groupId) ?? custom[0]}
				choices={custom}
				label={(g) => g?.name ?? channel.groupName}
				hint={(g) => (g ? `Numbers from ${g.start}` : undefined)}
				same={(a, b) => a?.id === b?.id}
				about="Its number follows the group: its place A to Z there."
				onselect={(g) => change({ groupId: g.id })}
				icon={Folder}
			/>
		{/if}
		<SwitchRow title="Favorite" checked={channel.isFavorite} icon={channel.isFavorite ? Star : StarOutline} onchange={(favorite) => change({ favorite })} />
		<SwitchRow
			title="Visible"
			summary={channel.isHidden ? 'Hidden from the guide' : undefined}
			checked={!channel.isHidden}
			icon={channel.isHidden ? VisibilityOff : Visibility}
			onchange={(visible) => change({ visible })}
		/>
		{#if !channel.fromLibrary}
			<Section title="Playback" />
			{#if detail.airsSeries || !detail.airsMovies}
				<ChoiceRow
					title="Series"
					value={detail.series}
					choices={detail.seriesChoices.map((c) => c.playback)}
					label={(p) => detail!.seriesChoices.find((c) => samePlayback(c.playback, p))?.label ?? ''}
					hint={(p) => detail!.seriesChoices.find((c) => samePlayback(c.playback, p))?.hint}
					same={samePlayback}
					summary={series?.label}
					onselect={(playback) => change({ series: playback })}
					icon={Tv}
				/>
			{/if}
			{#if detail.airsMovies || !detail.airsSeries}
				<ChoiceRow
					title="Movies"
					value={detail.movies}
					choices={detail.movieChoices.map((c) => c.playback)}
					label={(p) => detail!.movieChoices.find((c) => samePlayback(c.playback, p))?.label ?? ''}
					hint={(p) => detail!.movieChoices.find((c) => samePlayback(c.playback, p))?.hint}
					same={samePlayback}
					summary={(movies?.label ?? '') + (oncePerDay ? ', once daily' : '')}
					onselect={(playback) => change({ movies: playback })}
					icon={Movie}
				>
					{#snippet extra()}
						{#if detail!.fitsOncePerDay}
							<p class="field-label pt-2 text-text-muted">Repeats</p>
							<SwitchRow
								title="Once daily"
								summary="Each movie at most once in 24 hours."
								checked={detail!.oncePerDay}
								onchange={(once) => change({ oncePerDay: once })}
							/>
						{/if}
					{/snippet}
				</ChoiceRow>
			{/if}
			<Section title="Name and logo" />
			<TextRow
				title="Name"
				value={channel.name}
				icon={Title}
				onsave={async (name) => {
					if (!name) return 'Give the channel a name.';
					try {
						await api.updateChannel(id, { name });
						await load();
						return null;
					} catch (e) {
						return problem(e);
					}
				}}
			/>
			<Row title="Logo" summary={channel.logoUrl ? 'Your image' : 'None'} icon={channel.logoUrl ? Image : HideImage}>
				{#snippet end()}
					{#if channel.logoUrl}<span class="grid h-12 w-20 place-items-center"><img src={channel.logoUrl} alt="" class="max-h-full max-w-full object-contain" /></span>{/if}
				{/snippet}
			</Row>
			<input bind:this={picker} class="sr-only" type="file" accept="image/png,image/jpeg,image/webp,image/gif" onchange={upload} tabindex="-1" />
			<Buttons>
				<Button label={channel.logoUrl ? 'Change logo' : 'Upload logo'} icon={Upload} onclick={() => picker?.click()} />
				{#if channel.logoUrl}<Button label="Remove logo" icon={HideImage} onclick={clearLogo} />{/if}
			</Buttons>
			<Buttons foot>
				<DangerButton label="Delete channel" question="Delete {channel.name}?" explanation="Its schedule goes too." confirm="Delete" onconfirm={remove} />
			</Buttons>
		{/if}
	</Page>
{:else if error}
	<Page title="Channel" {trail}><Notice text={error} error /></Page>
{/if}
