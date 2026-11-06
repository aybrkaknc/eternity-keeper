"""Save list layouts and stable selection/open interactions."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, boot, check, summary

page = Page()
page.call("Page.enable")
E = page.eval

try:
	boot(page)
	page.wait_for("Eternity.SaveSearch.state.saves.length > 0", 60, "the save list")
	view_api = E("JSON.stringify({instance: typeof Eternity.SaveSearch.setViewMode, "
		"prototype: typeof SaveSearch.prototype.setViewMode})")
	check("view-toggle: method is attached to the live save-search instance",
		E("typeof Eternity.SaveSearch.setViewMode === 'function'"), view_api)
	if E("typeof Eternity.SaveSearch.setViewMode !== 'function'"):
		sys.exit(summary())
	E("Eternity.SaveSearch.setViewMode('list')")
	check("view-toggle: list view renders Explorer columns",
		E("$('#saveBlocks').hasClass('list-view')")		and E("$('#saveBlocks .save-list-header span').length") == 5
		and E("$('#saveBlocks .save-list-details').length") > 0)
	check("view-toggle: preference persists across page state",
		E("localStorage.getItem('ekSaveViewMode')") == "list")

	E("window.__saveTile = $('#saveBlocks .save-info').first()[0]; "
	  "window.__saveCollection = Eternity.SaveSearch.state.saves")
	E("Eternity.SaveSearch.select(0)")
	check("selection: selecting a save preserves its DOM node",
		E("$('#saveBlocks .save-info').first()[0] === window.__saveTile"))

	E("$('#saveActionLoad').click()")
	page.wait_for("Eternity.state.saveView && Eternity.SavedGame.state.saveData", 420,
		"the Load button's save to open")
	button_path = E("Eternity.SavedGame.state.info.absolutePath")
	check("load-button: opens the selected save and preserves the list row",
		bool(button_path) and E("$('#saveBlocks .save-info').first()[0] === window.__saveTile"))
	check("open: does not replace the save collection",
		E("window.__saveCollection === Eternity.SaveSearch.state.saves"))
	E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
	page.wait_for("Eternity.state.listView && $('#saveBlocks').is(':visible')", 60,
		"the save list to return")
	check("return: keeps the same list row and collection",
		E("$('#saveBlocks .save-info').first()[0] === window.__saveTile")
		and E("window.__saveCollection === Eternity.SaveSearch.state.saves"))

	E("$('#saveBlocks .save-info').first().trigger('dblclick')")
	page.wait_for("Eternity.state.saveView && Eternity.SavedGame.state.saveData", 420,
		"the double-clicked save to open")
	double_path = E("Eternity.SavedGame.state.info.absolutePath")
	check("double-click: opens the same save as Load",
		double_path == button_path)
	check("double-click open: preserves the list row and collection",
		E("$('#saveBlocks .save-info').first()[0] === window.__saveTile")
		and E("window.__saveCollection === Eternity.SaveSearch.state.saves"))
	E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
	page.wait_for("Eternity.state.listView && $('#saveBlocks').is(':visible')", 60,
		"the save list to return after double-click")
	check("double-click return: does not replace the save collection",
		E("window.__saveCollection === Eternity.SaveSearch.state.saves"))

	sys.exit(summary())
finally:
	page.ws.close()
