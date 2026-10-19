<script lang="ts">
	/** A radio's mark at the start of a Row: the accent ring and dot when checked, turning with the row's fill. */
	let { checked }: { checked: boolean } = $props();
</script>

<span
	class={[
		'grid size-5 place-items-center rounded-full border-2 group-hover/row:border-on-accent group-focus-visible/row:border-on-accent',
		checked ? 'border-accent' : 'border-text-muted'
	]}
	aria-hidden="true"
>
	{#if checked}<span class="size-2.5 rounded-full bg-accent group-hover/row:bg-on-accent group-focus-visible/row:bg-on-accent"></span>{/if}
</span>
