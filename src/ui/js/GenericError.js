/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2015 the authors.
 *
 *  Eternity Keeper is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  Eternity Keeper is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

var GenericError = function () {
	var self = this;

	var defaultState = {
		msg: ''
	};

	self.state = $.extend({}, defaultState);
	self.html = {};
	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		var alertBox = self.html.error.find('div');
		if (self.state.msg && self.state.msg.length > 0) {
			alertBox.empty()
				.append($('<i class="fa fa-exclamation-circle">').css('margin-right', '8px'))
				.append($('<span>').text(self.state.msg))
				.append(
					$('<button type="button" class="ek-error-close" title="Dismiss">&times;</button>')
						.click(() => self.render({msg: ''})));
			self.html.error.removeClass('ek-alert-enter').show().addClass('ek-alert-enter');
		} else {
			alertBox.empty();
			self.html.error.removeClass('ek-alert-enter').hide();
		}
	};
};

$.extend(GenericError.prototype, Renderer.prototype);
