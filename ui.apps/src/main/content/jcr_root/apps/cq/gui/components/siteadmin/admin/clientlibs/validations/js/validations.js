/*
 * ADOBE CONFIDENTIAL
 *
 * Copyright 2017 Adobe Systems Incorporated
 * All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Adobe Systems Incorporated and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Adobe Systems Incorporated and its
 * suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Adobe Systems Incorporated.
 *
 * Overlay from /libs/cq/gui/components/siteadmin/admin/clientlibs/validations/js/validations.js
 */
(function (window, document, Granite, $) {
  'use strict';

  /**
   * Validator for form name fields.
   */
  var formNamePattern = /^[a-zA-Z0-9_\\.\\/:\\-]+$/;
  $(window)
    .adaptTo('foundation-registry')
    .register('foundation.validation.validator', {
      selector: '[data-validation="admin.formfieldname"]',
      validate: function (el) {
        var valid = el.value && formNamePattern.test(el.value);

        if (!valid) {
          return Granite.I18n.get('This field should only contain numbers, letters, dashes and underscores.');
        }
      },
    });

  /**
   * Sanitize page name per EDS rules
   */
  function sanitizeName(name) {
    return name
      .toLowerCase()
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '');
  }

  function isValidName(name) {
    return sanitizeName(name) === name;
  }

  /**
   * Check if page path + name exceeds EDS 900 character limit
   */
  function isValidFullPagePath(name) {
    const EDSCharLimit = 900;
    const createPageWizardName = 'createpagewizard.html';
    const fullPagePath =
      document.location.pathname.substring(document.location.pathname.indexOf(createPageWizardName) + createPageWizardName.length) + name;
    return fullPagePath.length <= EDSCharLimit;
  }

  /**
   * Validator for page names
   */
  $(window)
    .adaptTo('foundation-registry')
    .register('foundation.validation.validator', {
      selector: '[data-foundation-validation~="admin.pagename"]',
      validate: function (el) {
        const valid = isValidName(el.value);
        if (!valid) {
          return Granite.I18n.get(`This field must only contain lowercase letters, numbers, and simple dash. e.g. "${sanitizeName(el.value)}"`);
        }

        if (!isValidFullPagePath(el.value)) {
          return Granite.I18n.get('Error: The full page path of an EDS page should not exceed 900 characters.');
        }
      },
    });

  /**
   * Validator for page title
   */
  $(window)
    .adaptTo('foundation-registry')
    .register('foundation.validation.validator', {
      selector: '[data-foundation-validation~="admin.pagetitle"]',
      validate: function (el) {
        document.querySelector('[name="pageName"]').value = sanitizeName(el.value);
      },
    });
})(window, document, Granite, Granite.$);
