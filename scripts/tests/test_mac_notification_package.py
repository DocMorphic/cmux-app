import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('mac_package', Path(__file__).resolve().parents[1] / 'package-mac-notification-companion.py')
PACKAGE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(PACKAGE)


class MacNotificationPackage(unittest.TestCase):
    def fixture(self):
        return {'CFBundleIdentifier': PACKAGE.BUNDLE_ID, 'LSEnvironment': {
            'CMUX_AUTH_ENVIRONMENT': 'production', 'CMUX_API_BASE_URL': 'https://cmux.com',
            'CMUX_IROH_BROKER_BASE_URL': 'https://cmux.com', 'CMUX_IROH_V2_ENVIRONMENT': 'production',
            'CMUX_IROH_V2_BASE_URL': 'https://cmux-iroh-v2.debussy.workers.dev',
            'CMUX_BUNDLED_CLI_PATH': '/Users/runner/work/build/bin/cmux',
            'CMUX_SHELL_INTEGRATION_DIR': '/Users/runner/work/shell', 'CMUXTERM_REPO_ROOT': '/Users/runner/work/source',
            'CMUX_SOCKET_PATH': '/tmp/cmux-debug-android-push.sock'}}

    def test_relocates_known_paths_without_mutating_original_or_isolated_socket(self):
        source = self.fixture(); original = copy.deepcopy(source)
        actual = PACKAGE.relocated_plist(source)
        self.assertEqual(source, original)
        self.assertFalse(PACKAGE.BUILD_PATH_KEYS & actual['LSEnvironment'].keys())
        self.assertEqual(actual['LSEnvironment']['CMUX_SOCKET_PATH'], '/tmp/cmux-debug-android-push.sock')

    def test_refuses_stable_and_untagged_bundles(self):
        for identity in ['com.cmuxterm.app', 'com.cmuxterm.app.debug', 'other']:
            with self.subTest(identity=identity):
                source = self.fixture(); source['CFBundleIdentifier'] = identity
                with self.assertRaises(ValueError): PACKAGE.relocated_plist(source)

    def test_refuses_development_account_or_transport_endpoints(self):
        for key in ['CMUX_AUTH_ENVIRONMENT', 'CMUX_API_BASE_URL', 'CMUX_IROH_BROKER_BASE_URL',
                    'CMUX_IROH_V2_ENVIRONMENT', 'CMUX_IROH_V2_BASE_URL']:
            with self.subTest(key=key):
                source = self.fixture(); source['LSEnvironment'][key] = 'development'
                with self.assertRaises(ValueError): PACKAGE.relocated_plist(source)

    def test_refuses_baked_private_account_and_helper_paths(self):
        for key in ['CMUX_AUTH_CREDENTIALS_FILE', 'CMUX_DEV_AUTH_PROFILE', 'CMUX_ANDROID_PUSH_CONFIG',
                    'CMUX_ANDROID_PUSH_NODE', 'CMUX_ANDROID_PUSH_HELPER']:
            with self.subTest(key=key):
                source = self.fixture(); source['LSEnvironment'][key] = '/private/fixture'
                with self.assertRaises(ValueError): PACKAGE.relocated_plist(source)

    def test_refuses_unknown_runner_paths(self):
        source = self.fixture(); source['LSEnvironment']['UNREVIEWED'] = '/Users/runner/build/unknown'
        with self.assertRaises(ValueError): PACKAGE.relocated_plist(source)
