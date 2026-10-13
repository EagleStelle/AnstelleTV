<script lang="ts">
	import type { Component } from 'svelte';
	import Row from './Row.svelte';

	/**
	 * On or off: the whole row flips it, the switch at its end says which. On a filled row the
	 * track turns the row's text color with an accent thumb, as the app's switch does.
	 */
	let { title, summary, icon, checked, onchange }: { title: string; summary?: string; icon?: Component<any>; checked: boolean; onchange: (checked: boolean) => void } =
		$props();
</script>

<Row {title} {summary} {icon} role="switch" aria-checked={checked} onclick={() => onchange(!checked)}>
	{#snippet end()}
		<span
			class={[
				'relative block h-8 w-13 rounded-full border-2 transition-colors',
				checked
					? 'border-accent bg-accent group-hover/row:border-on-accent group-hover/row:bg-on-accent group-focus-visible/row:border-on-accent group-focus-visible/row:bg-on-accent'
					: 'border-text-muted bg-surface-high group-hover/row:border-on-accent group-focus-visible/row:border-on-accent'
			]}
		>
			<span
				class={[
					'absolute rounded-full transition-all',
					checked
						? 'top-0.5 left-5.5 size-6 bg-on-accent group-hover/row:bg-accent group-focus-visible/row:bg-accent'
						: 'top-1.5 left-1.5 size-4 bg-text-muted group-hover/row:bg-on-accent group-focus-visible/row:bg-on-accent'
				]}
			></span>
		</span>
	{/snippet}
</Row>
