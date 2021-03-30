# -*- coding: utf-8 -*-
"""las_result_linenumber_parser.ipynb

Automatically generated by Colaboratory.

Original file is located at
    https://colab.research.google.com/drive/1q8EDKbQlL-GM74CBB14r_OZjqMNeyaQU
"""

import os
import pandas as pd

def make_path(directory, file_name, is_make_temp_dir=False):
    if is_make_temp_dir is True:
        directory = mkdtemp()
    if len(directory) >= 2 and not os.path.exists(directory):
        os.makedirs(directory)    
    return os.path.join(directory, file_name)

DIR = './' # data가 저장된 폴더를 지정합니다 
for f in [make_path(DIR, file) for file in os.listdir(path=DIR) if '.csv' in file]:
    csv_data = pd.read_csv(f)
    suggested_changes = csv_data['suggested change info']
    original_changes = csv_data['orig change info']
    bug_ids = csv_data['DFJ ID']

all_changes = False
### for all changes
if(all_changes):
    train_csv = pd.read_csv('/home/goodtaeeun/APR_Projects/APR/pool/outputs/las/all_changes_train.csv')
    test_csv = pd.read_csv('/home/goodtaeeun/APR_Projects/APR/pool/outputs/las/all_changes_test.csv')
    suggested_changes = train_csv['change info']
    original_changes = test_csv['change info']

print(original_changes)

line_dict = dict()
for id, change in zip(bug_ids, original_changes):
  start = change.find('(')
  end = change.find(')')
  substring = change[start+1:end]
  #print(id, substring)
  line_dict[id]= substring

df = pd.DataFrame()
df['dfj ID']= line_dict.keys()
df['blame line'] = line_dict.values()

df.to_csv('blame_lines.csv')
