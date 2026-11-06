/**
 * Pure filtering and ordering rules for the save selection panel.
 * Kept separate from DOM rendering so date order can be tested in plain Node.
 */
var SaveListModel = (function () {
	var saveName = info => {
		var userSaveName = info.userSaveName ? ' (' + info.userSaveName + ')' : '';
		return (info.playerName || '') + ' - ' + (info.sceneTitle || info.systemName || '') + userSaveName;
	};

	var filteredAndSorted = (saves, filterQuery, sortBy, matchesFilter) => {
		var entries = saves.map((info, index) => ({info: info, index: index}))
			.filter(entry => matchesFilter(entry.info, filterQuery));

		if (sortBy === 'date-desc') {
			entries.sort((a, b) => (Number(b.info.timestampMillis) || 0)
				- (Number(a.info.timestampMillis) || 0) || a.index - b.index);
		} else if (sortBy === 'date-asc') {
			entries.sort((a, b) => (Number(a.info.timestampMillis) || 0)
				- (Number(b.info.timestampMillis) || 0) || a.index - b.index);
		} else if (sortBy === 'name-asc') {
			entries.sort((a, b) => saveName(a.info).localeCompare(saveName(b.info))
				|| a.index - b.index);
		}

		return entries;
	};

	return {filteredAndSorted: filteredAndSorted};
})();

if (typeof module !== 'undefined' && module.exports) {
	module.exports = SaveListModel;
}
