import contextlib
import importlib.util
import io
import json
from pathlib import Path
from unittest.mock import patch


def load_probe(root):
    path = root / 'scripts' / 'powerplanner_probe.py'
    spec = importlib.util.spec_from_file_location('review_powerplanner_probe', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    root = Path(__file__).resolve().parents[3]
    probe = load_probe(root)
    calls = []

    def post(path, body):
        calls.append(path)
        return {
            '/LoginWeb': {'AccountId': 'synthetic-account', 'Session': 'synthetic-session'},
            '/GetSelectedSemesterId': {'SelectedSemesterId': 'synthetic-semester'},
            '/GetClassesAndSchedules': {'Classes': []},
            '/GetAgenda': {},
        }[path]

    output = io.StringIO()
    status = 0
    with patch.object(probe, 'post', post), patch('sys.argv', ['powerplanner_probe.py']), patch.dict(
        probe.os.environ,
        {'DECRASTINATION_PP_USERNAME': 'synthetic-user', 'DECRASTINATION_PP_PASSWORD': 'synthetic-password'},
        clear=True,
    ), contextlib.redirect_stdout(output):
        try:
            probe.main()
        except SystemExit as error:
            status = error.code
    result = {
        'case': 'successful HTTP response with missing agenda Items',
        'exit': status,
        'reported_zero_items': '0 agenda item(s)' in output.getvalue(),
        'reported_not_proven': 'Not proven:' in output.getvalue(),
        'calls': calls,
        'network_calls': 0,
    }
    print(json.dumps(result, indent=2))
    assert status == 0
    assert result['reported_zero_items']
    assert not result['reported_not_proven']


if __name__ == '__main__':
    main()
