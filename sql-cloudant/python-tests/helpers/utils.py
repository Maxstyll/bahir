# *******************************************************************************
# Copyright (c) 2015 IBM Corp.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ******************************************************************************/
import pytest
import subprocess
import os

import sys

app_script_dir = 'test-scripts/cloudantapp'
schema_script_dir = 'test-scripts/schema'
script_dir = None


class Utils(object):
    def __init__(self, test_scripts_path=None, run_schema_tests=False):
        self.scripts_path = test_scripts_path
        self.schema_tests = run_schema_tests
        super(Utils, self).__init__()
        if self.schema_tests:
            self.script_dir = schema_script_dir
        else:
            self.script_dir = app_script_dir

    def get_script_path(self, script_name):
        print(os.path.dirname(__file__))
        print(self.script_dir)

        return os.path.join(self.scripts_path, self.script_dir, script_name)

    def run_test(self, in_script, **kwargs):
        #__tracebackhide__ = True

        # spark-submit the script
        if kwargs.get('sparksubmit', None) is not None:
            command = [kwargs.get('sparksubmit', None)]
        else:
            command = [self.sparksubmit()]
        command.extend(["--master", "local[4]", "--jars", os.environ["CONNECTOR_JAR"], str(in_script)])
        print(str(command))
        proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        err, out = proc.communicate()
        if self.schema_tests:
            return proc.returncode, out.decode(encoding='UTF-8'), err.decode(encoding='UTF-8')
        else:
            # print spark log and stdout (when  py.test -s is used)
            # spark log is in stdout while test output is in stderr
            print(out.decode(encoding='UTF-8'))
            print(err.decode(encoding='UTF-8'))

            if proc.returncode != 0:
                pytest.fail(err.decode(encoding='UTF-8'))


    def sparksubmit(self):
        try:
            sys.path.append(os.path.join(os.environ["SPARK_HOME"], "python"))
            sys.path.append(os.path.join(os.environ["SPARK_HOME"], "python", "lib", "py4j-0.8.2.1-src.zip"))

        except KeyError:
            raise RuntimeError("Environment variable SPARK_HOME not set")

        return os.path.join(os.environ.get("SPARK_HOME"), "bin", "spark-submit")


def createSparkConf():
    from pyspark import SparkConf
    #test_properties = conftest.test_properties()

    conf = SparkConf()
    conf.set("cloudant.host", os.environ.get('CLOUDANT_ACCOUNT'))
    conf.set("cloudant.username", os.environ.get('CLOUDANT_USER'))
    conf.set("cloudant.password", os.environ.get('CLOUDANT_PASSWORD'))

    return conf
