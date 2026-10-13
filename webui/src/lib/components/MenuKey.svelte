<script lang="ts">
	import type { Component } from 'svelte';
	import MoreVert from '~icons/material-symbols/more-vert';
	import Button from './Button.svelte';
	import Dialog from './Dialog.svelte';
	import Row from './Row.svelte';

	/** The overflow mark at a row's end, a key of its own, opening the item's own menu. */
	interface Action {
		label: string;
		icon?: Component<any>;
		href?: string;
		download?: boolean;
		danger?: boolean;
		onclick?: () => void;
	}

	let { title, items, open = $bindable(false) }: { title: string; items: Action[]; open?: boolean } = $props();

	function run(action: Action) {
		open = false;
		action.onclick?.();
	}
</script>

<button
	type="button"
	aria-label="{title} menu"
	aria-haspopup="dialog"
	onclick={() => (open = true)}
	class="grid size-14 shrink-0 place-items-center rounded-control text-text-muted transition-colors hover:bg-accent hover:text-on-accent focus-visible:bg-accent focus-visible:text-on-accent"
>
	<MoreVert class="size-6" />
</button>

<Dialog bind:open {title}>
	<div class="-mx-3 flex flex-col gap-0.5">
		{#each items as action (action.label)}
			{#if action.href}
				<a
					class="group/link block rounded-control hover:bg-accent hover:text-on-accent focus-visible:bg-accent focus-visible:text-on-accent"
					href={action.href}
					download={action.download || undefined}
					onclick={() => (open = false)}
				>
					<Row title={action.label} icon={action.icon} />
				</a>
			{:else}
				<Row title={action.label} icon={action.icon} danger={action.danger} onclick={() => run(action)} />
			{/if}
		{/each}
	</div>
	{#snippet actions()}
		<Button label="Cancel" onclick={() => (open = false)} />
	{/snippet}
</Dialog>
