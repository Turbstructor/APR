import logging
import os  # nope
import sys
import pandas as pd
import getopt


def main(argv):
    try:
        opts, args = getopt.getopt(argv[1:], "d:", ["defects4J"])
    except getopt.GetoptError as err:
        print(err)
        sys.exit(2)
    is_D4J = False
    for o, a in opts:
        if o in ("-d", "--defects4J"):
            is_D4J = True
        else:
            assert False, "unhandled option"


    # pwd is APR/
    root = os.getcwd()
    # currently, we are running confix in APR/confix directory


    perfect_info = pd.read_csv(root+"/pool/commit_collector/inputs/input.csv",
                                names=['Project','Faulty file path','faulty line','buggy sha','url','dummy'])
    perfect_info_csv = perfect_info.values

    target_project = perfect_info_csv[1][0]
    perfect_faulty_path = perfect_info_csv[1][1]
    perfect_faulty_line = perfect_info_csv[1][2]
    buggy_sha = perfect_info_csv[1][3]

    perfect_faulty_class, foo = perfect_faulty_path.split(".")
    perfect_faulty_class = perfect_faulty_class.replace("/", ".")



    ## prepare setup before running confix

    ### for D4J projects
    if is_D4J == True:
        target_project, target_id = target_project.split('-')

        os.system("rm -rf "+root+"/target/* ;"
                    + "defects4j checkout -p "+target_project+" -v "+target_id+"b -w "+root+"/target/"+target_project)
        # print("Finish defects4j checkout")

        os.system("cp "+root+"/confix/coverages/"+target_project.lower()+"/"+target_project.lower()+target_id+"b/coverage-info.obj "
                    + root+"/target/"+target_project)
        # print("Finish copying coverage Info")

        target_dir = root+"/target/"+target_project

        os.system("cd "+target_dir+" ; "
                    + "defects4j compile")
        # print("Finish defects4j compile!!")

        os.system("cd "+target_dir+" ; "
                    + root+"/confix/scripts/config.sh "+target_project+" "+target_id + " " + perfect_faulty_class + " " + perfect_faulty_line)
        print("Finish config!!")

    ### for non-D4J projects
    # else:
        # build
        # fill up properties file


    # os.system("cd "+target_dir+" ; "
    #             + root+"/confix/scripts/confix.sh . >>log.txt 2>&1")
    os.system("cd "+target_dir+" ; "
            + "/usr/lib/jvm/java-8-openjdk-amd64/bin/java "
            + "-Xmx4g -cp ../../confix/lib/las.jar:../../confix/lib/confix-ami_torun.jar "
            + "-Duser.language=en -Duser.timezone=America/Los_Angeles com.github.thwak.confix.main.ConFix")
    print("Finish confix!!")

    os.system("mkdir "+root+"/target/"+target_project+"/patches")




if __name__ == '__main__':
    main(sys.argv)