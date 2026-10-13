<script lang="ts">
	/** A group's heading, in the OSD's field voice, more room above it than under it. */
	let { title }: { title: string } = $props();
</script>

<h2 class="field-label px-4 pt-6 pb-1 whitespace-pre text-text-muted">{title}</h2>
